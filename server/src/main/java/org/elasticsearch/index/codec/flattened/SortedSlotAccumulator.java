/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.flattened;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.IntroSorter;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.SuppressForbidden;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Accumulates {@code (keyOrd, docId, preEncodedSlotBytes)} triples written in document-visit order and exposes them sorted by {@code
 * (lexRank, docId)}.
 *
 * <h2>Protocol</h2>
 * <ol>
 *   <li>Call {@link #add} once per slot encountered during the document scan.</li>
 *   <li>After all documents have been scanned, compute the {@code lexRankOf} mapping
 *       (hash ordinal → lex rank, from {@link org.apache.lucene.util.BytesRefHash#sort})
 *       and call {@link #sortedCursor} to obtain a sorted cursor.</li>
 *   <li>Drain the cursor, then close it. Closing releases resources and deletes temp files.</li>
 * </ol>
 *
 * <h2>Sort strategy</h2>
 * During ingest, records accumulate in an in-memory buffer bounded by {@code maxBufferBytes}. Whenever appending the next record would
 * exceed that bound, the buffer is flushed to a raw (unsorted) spill file and reset, so heap use stays bounded regardless of total data
 * size. Records cannot be sorted at flush time because a record's sort key depends on {@code lexRankOf}, which is only known after ingest
 * finishes. When {@link #sortedCursor} is called (with {@code lexRankOf} now known):
 * <ul>
 *   <li>If nothing spilled: sort an index array over the resident buffer and return an in-memory cursor. No temp files are created.</li>
 *   <li>Otherwise: load each raw spill file (and the residual buffer) into memory one at a time, sort it by {@code (lexRankOf[keyOrd],
 *       docId)}, write it as a sorted-run temp file, then return a k-way merge cursor over all run files.</li>
 * </ul>
 *
 * <h2>Record format</h2>
 * Each record consists of three 4-byte <em>little-endian</em> integers ({@code keyOrd}, {@code docId}, {@code payloadLen}) followed by
 * {@code payloadLen} payload bytes. Little-endian matches Lucene's {@link org.apache.lucene.store.DataOutput#writeInt} / {@link
 * org.apache.lucene.store.DataInput#readInt}, which is what the external-sort run files are written and read through. The payload holds
 * pre-encoded column-block slot bytes: {@code [vint prefix][value bytes]} per slot, where prefix 0 = null and prefix N+1 = N value bytes.
 */
final class SortedSlotAccumulator implements Closeable {

    /** Byte offset of the {@code docId} int within the record header ({@code keyOrd} occupies bytes 0..3). */
    static final int DOC_ID_OFFSET = 4;
    /** Byte offset of the {@code payloadLen} int within the record header ({@code docId} occupies bytes 4..7). */
    static final int PAYLOAD_LEN_OFFSET = 8;
    /** Bytes of the fixed record header: {@code keyOrd + docId + payloadLen} (3 × 4). */
    static final int RECORD_HEADER_BYTES = 12;

    private final Directory directory;
    private final IOContext context;
    private final int maxBufferBytes;
    /** Prefix for temp file names (the segment data file name), tying spill/run temp files to the segment being written. */
    private final String tempFilePrefix;

    /** Growing in-memory buffer holding all unsorted records. */
    private byte[] buf;
    /** Number of bytes written into {@link #buf}. */
    private int bufLen;
    /** Number of records currently in {@link #buf} (reset to 0 on each spill). */
    private int numRecords;
    /** Names of raw, unsorted spill files, each holding one buffer's worth of records flushed when {@link #buf} filled during ingest. */
    private final List<String> spillFiles = new ArrayList<>();

    SortedSlotAccumulator(Directory directory, IOContext context, String tempFilePrefix, int maxBufferBytes) {
        this.directory = directory;
        this.context = context;
        this.tempFilePrefix = tempFilePrefix;
        this.maxBufferBytes = maxBufferBytes;
        this.buf = new byte[Math.min(4096, maxBufferBytes)];
    }

    /**
     * Records one slot in columnar block-payload framing {@code [vint prefix][value bytes]}, where {@code encodedPrefix} 0 = null and
     * {@code N+1} = N value bytes, and {@code value[valueOff..valueOff+valueLen)} holds the value bytes (empty when null).
     */
    void add(int keyOrd, int docId, int encodedPrefix, byte[] value, int valueOff, int valueLen) throws IOException {
        // Total size of this record
        final int payloadLen = vIntLength(encodedPrefix) + valueLen;
        final int recLen = RECORD_HEADER_BYTES + payloadLen;

        // Bound heap: once appending this record would exceed maxBufferBytes, flush the current buffer to a raw spill file and reset.
        if (bufLen > 0 && bufLen + recLen > maxBufferBytes) {
            spillCurrentBuffer();
        }

        // Ensure buf has room for the record; ArrayUtil.grow over-allocates so repeated appends amortise to O(1).
        if (bufLen + recLen > buf.length) {
            buf = ArrayUtil.grow(buf, bufLen + recLen);
        }

        // Write the fixed 12-byte header as three consecutive little-endian ints
        writeInt(buf, bufLen, keyOrd);
        writeInt(buf, bufLen + DOC_ID_OFFSET, docId);
        writeInt(buf, bufLen + PAYLOAD_LEN_OFFSET, payloadLen);

        // Write the payload right after the header:
        final int valueStart = writeVIntToArray(buf, bufLen + RECORD_HEADER_BYTES, encodedPrefix);
        if (valueLen > 0) {
            System.arraycopy(value, valueOff, buf, valueStart, valueLen);
        }

        // Advance the buffer cursor and record count to account for the record just appended.
        bufLen += recLen;
        numRecords++;
    }

    /**
     * Flushes the current buffer to a raw, unsorted spill file and resets it.
     */
    private void spillCurrentBuffer() throws IOException {
        final IndexOutput out = directory.createTempOutput(tempFilePrefix, "fss_raw", context);
        final String name = out.getName();

        try (out) {
            out.writeBytes(buf, 0, bufLen);
        } catch (Throwable t) {
            deleteFiles(name);
            throw t;
        }

        spillFiles.add(name);
        bufLen = 0;
        numRecords = 0;
    }

    /**
     * Returns a cursor over all records sorted by {@code (lexRankOf[keyOrd], docId)}.
     *
     * @param lexRankOf mapping from hash ordinal to lex rank, as produced by {@link org.apache.lucene.util.BytesRefHash#sort}:
     *                  {@code sortedOrds[lexRank] = hashOrd} → {@code lexRankOf[hashOrd] = lexRank}
     */
    SortedCursor sortedCursor(int[] lexRankOf) throws IOException {
        if (numRecords == 0 && spillFiles.isEmpty()) {
            return SortedCursor.EMPTY;
        }
        if (spillFiles.isEmpty()) {
            return sortInMemory(lexRankOf);
        }
        return externalSort(lexRankOf);
    }

    // -----------------------------------------------------------------------
    // In-memory sort (data fits within maxBufferBytes)
    // -----------------------------------------------------------------------

    private SortedCursor sortInMemory(int[] lexRankOf) {
        final int n = numRecords;
        final int[] offset = new int[n];
        final long[] key = new long[n];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            offset[i] = pos;
            key[i] = sortKey(lexRankOf[readInt(buf, pos)], readInt(buf, pos + DOC_ID_OFFSET));
            pos += RECORD_HEADER_BYTES + readInt(buf, pos + PAYLOAD_LEN_OFFSET);
        }
        sortParallel(key, offset, n);
        // Transfer buf ownership to the cursor (its lifecycle now owns buf).
        final byte[] data = buf;
        buf = null;
        return new InMemoryCursor(data, offset, n, lexRankOf);
    }

    // -----------------------------------------------------------------------
    // External sort (buffer spilled to raw files during ingest)
    // -----------------------------------------------------------------------

    /**
     * Turns each raw spill file (and the residual in-memory buffer) into a run file sorted by {@code (lexRankOf[keyOrd], docId)}, then
     * returns a cursor over them. The lex-rank sort key is only known now, after ingest, which is why the spill files were written raw.
     * Because a spill always leaves the triggering record in the buffer, the residual is non-empty here, so there are always at least two
     * runs and the merge cursor is used.
     */
    private SortedCursor externalSort(int[] lexRankOf) throws IOException {
        final List<String> runFiles = new ArrayList<>();
        boolean success = false;
        try {
            for (final String spill : spillFiles) {
                runFiles.add(sortSpillFile(spill, lexRankOf));
            }
            if (bufLen > 0) {
                runFiles.add(writeSortedRun(buf, bufLen, lexRankOf));
            }
            buf = null;
            // A spill always leaves its triggering record in the buffer, so the residual run above is always present alongside at least one
            // spill run: there are always >= 2 runs here.
            assert runFiles.size() >= 2 : runFiles.size();
            final SortedCursor cursor = new MergeCursor(directory, context, runFiles, lexRankOf);
            success = true;
            return cursor;
        } finally {
            // Raw spill files have been fully read into sorted runs; drop them. On failure, also drop the runs we created.
            deleteFiles(spillFiles.toArray(new String[0]));
            spillFiles.clear();
            if (success == false) {
                deleteFiles(runFiles.toArray(new String[0]));
            }
        }
    }

    /**
     * Loads a raw spill file fully into memory, sorts it by {@code (lexRankOf[keyOrd], docId)}, and writes it as a sorted run file.
     * Returns the name of the run file.
     */
    private String sortSpillFile(String name, int[] lexRankOf) throws IOException {
        final byte[] chunk;
        try (IndexInput in = directory.openInput(name, context)) {
            chunk = new byte[Math.toIntExact(in.length())];
            in.readBytes(chunk, 0, chunk.length);
        }
        return writeSortedRun(chunk, chunk.length, lexRankOf);
    }

    /**
     * Sorts the records in {@code chunk[0..chunkLen)} by {@code (lexRankOf[keyOrd], docId)} and writes them to a new sorted run file.
     * Returns the name of the run file.
     */
    private String writeSortedRun(byte[] chunk, int chunkLen, int[] lexRankOf) throws IOException {
        // Count the number of records
        int numRecs = 0;
        for (int p = 0; p < chunkLen; p += RECORD_HEADER_BYTES + readInt(chunk, p + PAYLOAD_LEN_OFFSET)) {
            numRecs++;
        }

        // Records are variable-length blobs, so instead of shuffling them we sort two small fixed-width arrays that describe them:
        // - offset[i] is where record i starts in chunk
        // - key[i] is the value we sort on (lexRank + docId)
        // sortParallel sorts key ascending and drags offset along, so afterwards offset lists the record start offsets in final sorted
        // order
        // and the payload bytes never move. The emit loop below then copies records out in that order.
        final int[] offset = new int[numRecs];
        final long[] key = new long[numRecs];
        int pos = 0;
        for (int i = 0; i < numRecs; i++) {
            offset[i] = pos;
            // lexRankOf maps the stored keyOrd to its lexicographic rank, so records sort into final column order here.
            key[i] = sortKey(lexRankOf[readInt(chunk, pos)], readInt(chunk, pos + DOC_ID_OFFSET));
            pos += RECORD_HEADER_BYTES + readInt(chunk, pos + PAYLOAD_LEN_OFFSET);
        }

        // Sort by key with offset as the stable tiebreak, so equal (lexRank, docId) records keep document-visit order within this run.
        sortParallel(key, offset, numRecs);

        // Emit each record's raw bytes in sorted order to the run file.
        final IndexOutput runOut = directory.createTempOutput(tempFilePrefix, "fss_run", context);
        final String name = runOut.getName();
        try {
            for (int i = 0; i < numRecs; i++) {
                final int payloadLen = readInt(chunk, offset[i] + PAYLOAD_LEN_OFFSET);
                runOut.writeBytes(chunk, offset[i], RECORD_HEADER_BYTES + payloadLen);
            }
        } finally {
            runOut.close();
        }
        return name;
    }

    // -----------------------------------------------------------------------
    // Sort helpers
    // -----------------------------------------------------------------------

    private static long sortKey(int lexRank, int docId) {
        return ((long) lexRank << 32) | (docId & 0xFFFFFFFFL);
    }

    /**
     * Sorts {@code key[0..numRecords)} ascending in-place, applying the same permutation to {@code offset[0..numRecords)}.
     *
     * <p>When two records share the same primary sort key {@code (lexRank, docId)} (ie. multiple slot values for the same sub-field in the
     * same document), their relative order is resolved by {@code offset[i]}, which is their byte offset within the buffer or chunk and
     * increases monotonically with insertion order. This preserves the original document-visit order of multiple values for the same key,
     * which the columnar reader exposes as the array order for that field.
     */
    private static void sortParallel(final long[] key, final int[] offset, final int numRecords) {
        new IntroSorter() {
            private long pivotKey;
            private int pivotOff;

            @Override
            protected int compare(int i, int j) {
                final int c = Long.compare(key[i], key[j]);
                // Stable tiebreak: smaller byte offset = earlier insertion = earlier array slot.
                return c != 0 ? c : Integer.compare(offset[i], offset[j]);
            }

            @Override
            protected void swap(int i, int j) {
                final long tmpKey = key[i];
                key[i] = key[j];
                key[j] = tmpKey;
                final int tmpOff = offset[i];
                offset[i] = offset[j];
                offset[j] = tmpOff;
            }

            @Override
            protected void setPivot(int i) {
                pivotKey = key[i];
                pivotOff = offset[i];
            }

            @Override
            protected int comparePivot(int j) {
                final int c = Long.compare(pivotKey, key[j]);
                return c != 0 ? c : Integer.compare(pivotOff, offset[j]);
            }
        }.sort(0, numRecords);
    }

    // -----------------------------------------------------------------------
    // Cursors - which one sortedCursor() returns depends on how much was ingested:
    // SortedCursor.EMPTY - nothing was added; next() is always false.
    // InMemoryCursor - everything fit in the resident buffer (no spill); walks a sorted index array over that buffer, no temp files.
    // MergeCursor - the buffer spilled to disk; k-way merges the sorted run files, one SortedRun element per run, via a min-heap.
    // -----------------------------------------------------------------------

    /**
     * Sorted record cursor returned by {@link #sortedCursor}. Each {@link #next} call advances to the next record; field accessors return
     * values for the current record. Must be closed after use to release temporary files.
     */
    abstract static class SortedCursor implements Closeable {

        static final SortedCursor EMPTY = new SortedCursor() {
            @Override
            public boolean next() {
                return false;
            }

            @Override
            public int lexRank() {
                return -1;
            }

            @Override
            public int docId() {
                return -1;
            }

            @Override
            public byte[] payloadBytes() {
                return new byte[0];
            }

            @Override
            public int payloadOffset() {
                return 0;
            }

            @Override
            public int payloadLength() {
                return 0;
            }

            @Override
            public void close() {}
        };

        abstract boolean next() throws IOException;

        abstract int lexRank();

        abstract int docId();

        abstract byte[] payloadBytes();

        abstract int payloadOffset();

        abstract int payloadLength();
    }

    /** In-memory cursor over a sorted index array. */
    private static final class InMemoryCursor extends SortedCursor {
        private final byte[] buf;
        private final int[] offset;
        private final int count;
        private final int[] lexRankOf;
        private int idx = -1;
        private int curLexRank;
        private int curDocId;

        InMemoryCursor(byte[] buf, int[] offset, int count, int[] lexRankOf) {
            this.buf = buf;
            this.offset = offset;
            this.count = count;
            this.lexRankOf = lexRankOf;
        }

        @Override
        public boolean next() {
            if (++idx >= count) return false;
            final int o = offset[idx];
            curLexRank = lexRankOf[readInt(buf, o)];
            curDocId = readInt(buf, o + DOC_ID_OFFSET);
            return true;
        }

        @Override
        public int lexRank() {
            return curLexRank;
        }

        @Override
        public int docId() {
            return curDocId;
        }

        @Override
        public byte[] payloadBytes() {
            return buf;
        }

        @Override
        public int payloadOffset() {
            return offset[idx] + RECORD_HEADER_BYTES;
        }

        @Override
        public int payloadLength() {
            return readInt(buf, offset[idx] + PAYLOAD_LEN_OFFSET);
        }

        @Override
        public void close() {}
    }

    /** One sorted run file, read sequentially; the per-run element merged by {@link MergeCursor}. Not a {@link SortedCursor} itself. */
    private static final class SortedRun implements Closeable {
        private final IndexInput in;
        private final Directory dir;
        private final String fileName;
        private final int[] lexRankOf;
        private final long fileLen;
        // Position of this run among the merged runs (earlier runs hold strictly-earlier insertions); the merge tiebreaker for equal keys.
        int runIndex;
        int curLexRank;
        int curDocId;
        int curPayloadLen;
        byte[] payload = new byte[64];

        SortedRun(IndexInput in, Directory dir, String fileName, int[] lexRankOf) {
            this.in = in;
            this.dir = dir;
            this.fileName = fileName;
            this.lexRankOf = lexRankOf;
            this.fileLen = in.length();
        }

        boolean next() throws IOException {
            if (in.getFilePointer() >= fileLen) return false;
            curLexRank = lexRankOf[in.readInt()];
            curDocId = in.readInt();
            curPayloadLen = in.readInt();
            if (curPayloadLen > payload.length) {
                payload = new byte[ArrayUtil.oversize(curPayloadLen, 1)];
            }
            in.readBytes(payload, 0, curPayloadLen);
            return true;
        }

        @Override
        @SuppressForbidden(reason = "require usage of Lucene's IOUtils#deleteFilesIgnoringExceptions(...)")
        public void close() throws IOException {
            IOUtils.close(in);
            org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(dir, fileName);
        }
    }

    /** K-way merge cursor over multiple sorted run files, backed by a min-heap. */
    private static final class MergeCursor extends SortedCursor {
        private final List<SortedRun> runs;
        private final PriorityQueue<SortedRun> heap;
        private SortedRun current;

        MergeCursor(Directory dir, IOContext context, List<String> runFiles, int[] lexRankOf) throws IOException {
            runs = new ArrayList<>(runFiles.size());

            // Order by (lexRank, docId), then by run index so equal keys keep global insertion order.
            // noinspection ComparatorCombinators
            heap = new PriorityQueue<>((a, b) -> {
                int cmp = Integer.compare(a.curLexRank, b.curLexRank);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(a.curDocId, b.curDocId);
                return cmp != 0 ? cmp : Integer.compare(a.runIndex, b.runIndex);
            });

            for (int i = 0; i < runFiles.size(); i++) {
                final String f = runFiles.get(i);
                final SortedRun c = new SortedRun(dir.openInput(f, context), dir, f, lexRankOf);
                c.runIndex = i;
                runs.add(c);
                if (c.next()) heap.offer(c);
            }
        }

        @Override
        public boolean next() throws IOException {
            if (current != null) {
                if (current.next()) heap.offer(current);
                current = null;
            }
            if (heap.isEmpty()) return false;
            current = heap.poll();
            return true;
        }

        @Override
        public int lexRank() {
            return current.curLexRank;
        }

        @Override
        public int docId() {
            return current.curDocId;
        }

        @Override
        public byte[] payloadBytes() {
            return current.payload;
        }

        @Override
        public int payloadOffset() {
            return 0;
        }

        @Override
        public int payloadLength() {
            return current.curPayloadLen;
        }

        @Override
        public void close() throws IOException {
            IOUtils.close(runs);
        }
    }

    // I/O helpers — little-endian to match Lucene's DataInput#readInt /
    // DataOutput#writeInt, since run files are read back through IndexInput.

    static int readInt(byte[] buf, int offset) {
        return (int) BitUtil.VH_LE_INT.get(buf, offset);
    }

    private static void writeInt(byte[] buf, int offset, int v) {
        BitUtil.VH_LE_INT.set(buf, offset, v);
    }

    /** Number of bytes non-negative {@code v} occupies when VInt-encoded. */
    private static int vIntLength(int v) {
        int len = 1;
        while ((v & ~0x7F) != 0) {
            v >>>= 7;
            len++;
        }
        return len;
    }

    /** Encodes {@code v} as a VInt into {@code buf[offset..]} and returns the offset just past it. */
    private static int writeVIntToArray(byte[] buf, int offset, int v) {
        while ((v & ~0x7F) != 0) {
            buf[offset++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[offset++] = (byte) v;
        return offset;
    }

    @SuppressForbidden(reason = "require usage of Lucene's IOUtils#deleteFilesIgnoringExceptions(...)")
    private void deleteFiles(String... names) {
        org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(directory, names);
    }

    @Override
    public void close() {
        buf = null;
        // Delete any spill files not already consumed by externalSort (e.g. sortedCursor was never called, or ingest failed part-way).
        deleteFiles(spillFiles.toArray(new String[0]));
        spillFiles.clear();
    }
}
