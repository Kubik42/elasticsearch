/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.flattened;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocIDMerger;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.ByteBlockPool;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.codec.FilterDocValuesProducer;
import org.elasticsearch.index.codec.perfield.XPerFieldDocValuesFormat;
import org.elasticsearch.index.codec.tsdb.DISIAccumulator;
import org.elasticsearch.index.engine.PruningMergePolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.VERSION_CURRENT;

/**
 * Doc values consumer for the columnar flattened format.
 *
 * <p>Only {@link org.apache.lucene.index.DocValuesType#BINARY} doc values are supported. Any attempt to write other types throws {@link
 * UnsupportedOperationException}, because this format is only dispatched for the {@code ._keyed} binary field of a {@code flattened} field
 * with {@code preserve_leaf_arrays: exact} in a strict-columnar index.
 *
 * <p>Merge is handled by {@link #mergeBinaryField}: when all source segments are columnar, it performs a column-wise merge that reads each
 * sub-field's column sequentially and writes blocks directly into the output, avoiding the expensive round-trip through {@link
 * BinaryDocValues} blobs and {@link #addBinaryField}. When any source segment is not columnar (mixed merge), the inherited default from
 * {@link DocValuesConsumer} is used instead.
 *
 * <h2>Meta layout per field</h2>
 *
 * <pre>
 * int   fieldNumber
 * byte  FLATTENED_COLUMNAR_BINARY (0)
 * long  dataOffset                  — start of this field's data region in the data file
 * long  docsWithFieldOffset         — -2 = empty, -1 = dense, else IndexedDISI offset in data
 * long  docsWithFieldLength
 * short jumpTableEntryCount         — -1 for dense/empty
 * byte  denseRankPower              — -1 for dense/empty
 * int   numDocsWithField
 * int   numKeys
 * long  keyDictOffset               — key dictionary offset in data file
 * long  keyDictLength
 * vlong totalKeyBytes               — summed length of all key bytes, for sizing the reader's flat key pool in one allocation
 * long  columnAddressTableOffset    — column address table offset in data file
 * vint  maxUncompressedBlockLen     — for reader buffer pre-sizing
 * vint  maxDocsPerBlock             — for reader buffer pre-sizing
 * long  dataLength                  — total bytes of this field's data region
 * </pre>
 */
final class FlattenedDocValuesConsumer extends DocValuesConsumer {

    /** Sentinel in meta to mark end-of-fields. */
    private static final int FIELD_EOF = -1;

    /** DV type code written to the meta file for our one supported type. */
    static final byte FLATTENED_COLUMNAR_BINARY = 0;

    /**
     * Default dense-rank power (every 512 docIDs). Valid range for {@link DISIAccumulator}: 7–15 or -1.
     */
    static final byte DEFAULT_DENSE_RANK_POWER = (byte) 9;

    private IndexOutput data;
    private IndexOutput meta;

    private final int maxDoc;
    private final SegmentWriteState state;
    private final int targetBlockBytes;
    private final int maxDocsPerBlock;
    private final int minCompressBytes;
    private final int maxBufferedBytes;

    FlattenedDocValuesConsumer(
        SegmentWriteState state,
        String dataCodec,
        String dataExtension,
        String metaCodec,
        String metaExtension,
        int targetBlockBytes,
        int maxDocsPerBlock,
        int minCompressBytes,
        int maxBufferedBytes
    ) throws IOException {
        this.state = state;
        this.maxDoc = state.segmentInfo.maxDoc();
        this.targetBlockBytes = targetBlockBytes;
        this.maxDocsPerBlock = maxDocsPerBlock;
        this.minCompressBytes = minCompressBytes;
        this.maxBufferedBytes = maxBufferedBytes;

        boolean success = false;
        try {
            final String dataName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
            data = state.directory.createOutput(dataName, state.context);
            CodecUtil.writeIndexHeader(data, dataCodec, VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);

            final String metaName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);
            meta = state.directory.createOutput(metaName, state.context);
            CodecUtil.writeIndexHeader(meta, metaCodec, VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);

            success = true;
        } finally {
            if (success == false) {
                IOUtils.closeWhileHandlingException(this);
            }
        }
    }

    @Override
    public void addBinaryField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        meta.writeInt(field.number);
        meta.writeByte(FLATTENED_COLUMNAR_BINARY);
        final long dataOffset = data.getFilePointer();
        meta.writeLong(dataOffset);

        // Set up the key dictionary, the doc-presence accumulator, and the external-sort accumulator that will reorder slots into columns.
        final BytesRefHash keyHash = new BytesRefHash(new ByteBlockPool(new ByteBlockPool.DirectAllocator()));
        final DISIAccumulator disiAcc = new DISIAccumulator(state.directory, state.context, data, DEFAULT_DENSE_RANK_POWER);
        final SortedSlotAccumulator slotAcc = new SortedSlotAccumulator(state.directory, state.context, data.getName(), maxBufferedBytes);

        try {
            // 1. Ingest: split each doc's blob into (keyOrd, docId, slot) triples fed to the sort accumulator; record doc presence.
            final DocPresence presence = ingestBinaryValues(field, valuesProducer, keyHash, disiAcc, slotAcc);

            // 2. Build the hashOrd -> lexRank map so the accumulator can order slots by the key's lexicographic rank.
            final int numKeys = keyHash.size();
            final int[] sortedOrds = keyHash.sort(); // sortedOrds[lexRank] = hashOrd
            final int[] lexRankOf = new int[numKeys];
            for (int lr = 0; lr < numKeys; lr++) {
                lexRankOf[sortedOrds[lr]] = lr;
            }

            // 3. Sort slots by (lexRank, docId) and write one column per key directly into data.
            final FieldBlockWriter.ColumnAddress[] addresses = new FieldBlockWriter.ColumnAddress[numKeys];
            final BlockStats blockStats = writeSortedColumns(slotAcc, lexRankOf, addresses);

            // 4. Write DISI (sparse doc presence) after all column data.
            final DisiSection disi = writeDisiSection(disiAcc, presence.numDocsWithField(), presence.isDense());

            // 5. Write the key dictionary in lex order (ordinal = lex rank), enabling binary-search key lookup on read.
            final long keyDictOffset = data.getFilePointer();
            final long totalKeyBytes = writeKeyDictionary(keyHash, numKeys, sortedOrds);
            final long keyDictLength = data.getFilePointer() - keyDictOffset;

            // 6. Write the column address table: one fixed-width entry per key (in lex ordinal order) locating its column.
            final long columnAddressTableOffset = data.getFilePointer();
            writeColumnAddressTable(addresses, numKeys);

            final long dataLength = data.getFilePointer() - dataOffset;

            // 7. Write the meta fields for this field (must match the layout documented on the class and read by the producer).
            writeFieldMeta(
                disi,
                presence.numDocsWithField(),
                numKeys,
                keyDictOffset,
                keyDictLength,
                totalKeyBytes,
                columnAddressTableOffset,
                blockStats.maxUncompressedBlockLen(),
                blockStats.maxDocsPerBlockSeen(),
                dataLength
            );
        } finally {
            IOUtils.close(disiAcc, slotAcc);
        }
    }

    /**
     * Iterates every doc that has the field, splitting each doc's KeyedArrayOrderInlineNull blob into individual (keyOrd, docId, slot)
     * triples fed to the sort accumulator, interning keys into {@code keyHash} and recording doc presence/denseness into {@code disiAcc}.
     */
    private DocPresence ingestBinaryValues(
        FieldInfo field,
        DocValuesProducer valuesProducer,
        BytesRefHash keyHash,
        DISIAccumulator disiAcc,
        SortedSlotAccumulator slotAcc
    ) throws IOException {
        int numDocsWithField = 0;
        int prevDocId = -1;
        boolean isDense = true;

        final BinaryDocValues values = valuesProducer.getBinary(field);
        final ByteArrayDataInput vintReader = new ByteArrayDataInput();
        for (int doc = values.nextDoc(); doc != NO_MORE_DOCS; doc = values.nextDoc()) {
            numDocsWithField++;
            if (doc != prevDocId + 1) isDense = false;
            prevDocId = doc;
            disiAcc.addDocId(doc);

            final BytesRef blob = values.binaryValue();
            // Parse KeyedArrayOrderInlineNull framing: [vint encodedLen][key bytes]\0[value bytes]...
            int pos = blob.offset;
            final int end = blob.offset + blob.length;
            vintReader.reset(blob.bytes, blob.offset, blob.length);
            while (pos < end) {
                // Decode the null-biased value length: 0 marks a null value, otherwise it encodes (valueLen + 1).
                vintReader.setPosition(pos);
                final int encodedLen = vintReader.readVInt();
                pos = vintReader.getPosition();
                final boolean isNull = (encodedLen == 0);
                final int valueLen = isNull ? 0 : (encodedLen - 1);

                // Scan forward to the \0 separator to delimit the key bytes; value bytes follow the separator.
                final int keyStart = pos;
                int sep = keyStart;
                while (sep < end && blob.bytes[sep] != 0) {
                    sep++;
                }
                final int keyLen = sep - keyStart;
                pos = sep + 1; // skip \0; pos now points to value bytes

                // Intern the key to a stable per-field ordinal (BytesRefHash returns -(existingOrd)-1 on a repeat).
                int ord = keyHash.add(new BytesRef(blob.bytes, keyStart, keyLen));
                if (ord < 0) ord = -ord - 1; // already present

                // Store slot in accumulator as [vint encodedLen][value bytes]; encodedLen (the null-biased value length) is unchanged.
                // Pass the bytes directly into add() along with the offset and len. This eliminates the need to do a transit arraycopy.
                slotAcc.add(ord, doc, encodedLen, blob.bytes, pos, valueLen);
                pos += valueLen;
            }
        }
        return new DocPresence(numDocsWithField, isDense);
    }

    /**
     * Walks the sort accumulator's cursor (slots already ordered by (lexRank, docId)), buffering each doc's slots and emitting a column per
     * key directly into data. Records each key's {@link FieldBlockWriter.ColumnAddress} into {@code addresses} and returns block stats.
     */
    private BlockStats writeSortedColumns(SortedSlotAccumulator slotAcc, int[] lexRankOf, FieldBlockWriter.ColumnAddress[] addresses)
        throws IOException {
        int maxUncompressedBlockLen = 0;
        int maxDocsPerBlockSeen = 0;

        try (SortedSlotAccumulator.SortedCursor cursor = slotAcc.sortedCursor(lexRankOf)) {
            // Allocate one writer and reuse it across all columns via reset().
            // This avoids allocating ~128 KiB of block-accumulation arrays and a ZSTD compressor per key.
            final FieldBlockWriter writer = new FieldBlockWriter(data, targetBlockBytes, maxDocsPerBlock, minCompressBytes);
            int prevLexRank = -1;
            int prevDoc = -1;
            int slotCount = 0;
            // Per-doc accumulation buffers in the new format: decoded lengths and raw value bytes.
            int[] docSlotLens = new int[8];
            byte[] docValues = new byte[256];
            int docValuesLen = 0;
            final ByteArrayDataInput vintReader = new ByteArrayDataInput();

            // The cursor yields slots already sorted by (lexRank, docId). Walk them once, buffering the current doc's slots and
            // emitting a doc to the writer on each doc boundary, and finishing a column on each key boundary.
            while (cursor.next()) {
                final int lr = cursor.lexRank();
                final int curDoc = cursor.docId();

                // Key boundary: flush the last buffered doc, finish this key's column, record its address, and reset the writer.
                if (lr != prevLexRank) {
                    if (prevLexRank >= 0) {
                        if (prevDoc >= 0) {
                            writer.addDocSlots(prevDoc, slotCount, docSlotLens, 0, docValues, 0, docValuesLen);
                        }
                        writer.finish();
                        maxUncompressedBlockLen = Math.max(maxUncompressedBlockLen, writer.maxUncompressedBlockLen);
                        maxDocsPerBlockSeen = Math.max(maxDocsPerBlockSeen, writer.maxDocsPerBlockSeen);
                        addresses[prevLexRank] = writer.columnAddress();
                        writer.reset(data);
                    }
                    prevLexRank = lr;
                    prevDoc = -1;
                    slotCount = 0;
                    docValuesLen = 0;
                }

                // Doc boundary within the same key: flush the previous doc's buffered slots and start buffering the new doc.
                if (curDoc != prevDoc) {
                    if (prevDoc >= 0) {
                        writer.addDocSlots(prevDoc, slotCount, docSlotLens, 0, docValues, 0, docValuesLen);
                    }
                    prevDoc = curDoc;
                    slotCount = 0;
                    docValuesLen = 0;
                }

                // Append this slot to the current doc's buffer. Decode the accumulator record: [vint encodedLen][value bytes].
                // encodedLen == 0 → null; encodedLen == N+1 → N value bytes.
                final byte[] payloadBytes = cursor.payloadBytes();
                final int payloadOffset = cursor.payloadOffset();
                vintReader.reset(payloadBytes, payloadOffset, payloadBytes.length - payloadOffset);
                final int encodedLen = vintReader.readVInt();
                final int valuePos = vintReader.getPosition();
                final int valueLen = (encodedLen == 0) ? -1 : (encodedLen - 1); // -1 = null
                docSlotLens = ArrayUtil.grow(docSlotLens, slotCount + 1);
                docSlotLens[slotCount] = valueLen;
                if (valueLen > 0) {
                    docValues = ArrayUtil.grow(docValues, docValuesLen + valueLen);
                    System.arraycopy(payloadBytes, valuePos, docValues, docValuesLen, valueLen);
                    docValuesLen += valueLen;
                }
                slotCount++;
            }

            // Flush the last doc and last key.
            if (prevLexRank >= 0) {
                if (prevDoc >= 0) {
                    writer.addDocSlots(prevDoc, slotCount, docSlotLens, 0, docValues, 0, docValuesLen);
                }
                writer.finish();
                maxUncompressedBlockLen = Math.max(maxUncompressedBlockLen, writer.maxUncompressedBlockLen);
                maxDocsPerBlockSeen = Math.max(maxDocsPerBlockSeen, writer.maxDocsPerBlockSeen);
                addresses[prevLexRank] = writer.columnAddress();
            }
        }
        return new BlockStats(maxUncompressedBlockLen, maxDocsPerBlockSeen);
    }

    /** Doc-presence result of the ingest pass. */
    private record DocPresence(int numDocsWithField, boolean isDense) {}

    /** Reader-buffer pre-sizing hints gathered while writing columns. */
    private record BlockStats(int maxUncompressedBlockLen, int maxDocsPerBlockSeen) {}

    /**
     * {@inheritDoc}
     *
     * <p>When all source segments use the columnar flattened format, performs a column-wise merge: for each merged sub-field key (in lex
     * order), a {@link DocIDMerger} walks source documents in target-docID order and bulk-copies each doc's slot bytes directly from the
     * decompressed source block into a new block. Blocks are written straight into the data output — no temp files, no splice pass, no
     * {@link BytesRefHash} re-hashing. Work is O(total slots) with sequential I/O per source segment.
     *
     * <p>Falls back to the inherited row-based merge (which calls {@link #addBinaryField}) when any source segment's producer cannot be
     * unwrapped to a {@link FlattenedDocValuesProducer}. In practice this covers: a genuinely mixed old-format + columnar merge (segments
     * imported by shrink/split/restore, or a codec setting change); a source read through Lucene's (non-ES) per-field reader; and a segment
     * carrying doc-values field updates, which Lucene reads via a {@code SegmentDocValuesProducer} the unwrap chain does not recognize.
     *
     * <p>The {@link PruningMergePolicy} branch in {@code tryColumnWiseMerge} is an unwrap step, not a live fallback trigger for this field:
     * pruning only targets the recovery-source and {@code _seq_no} numeric fields, never a {@code ._keyed} binary field, so it never forces
     * the fallback here — it only peels the pruning wrapper off so the fast path can reach the underlying producer.
     */
    @Override
    public void mergeBinaryField(FieldInfo mergeFieldInfo, MergeState mergeState) throws IOException {
        if (tryColumnWiseMerge(mergeFieldInfo, mergeState) == false) {
            super.mergeBinaryField(mergeFieldInfo, mergeState);
        }
    }

    /**
     * Attempts the column-wise merge. Returns {@code true} on success, {@code false} if any source segment cannot be unwrapped to a {@link
     * FlattenedDocValuesProducer} (caller should fall back to the inherited merge).
     */
    private boolean tryColumnWiseMerge(FieldInfo mergeFieldInfo, MergeState mergeState) throws IOException {
        final int numSources = mergeState.docValuesProducers.length;
        final FlattenedDocValuesProducer[] producers = new FlattenedDocValuesProducer[numSources];

        // Attempt to resolve each source segment's producer down to the concrete FlattenedDocValuesProducer
        for (int i = 0; i < numSources; i++) {
            // The merged field may be absent from this segment's schema (ex. it was introduced by a later segment) => no data to contribute
            final FieldInfo fi = mergeState.fieldInfos[i].fieldInfo(mergeFieldInfo.name);
            if (fi == null) {
                producers[i] = null; // segment does not have this field — OK
                continue;
            }

            // A null producer means the segment carries no doc values at all (ex. an all-deleted segment) => no data to contribute
            DocValuesProducer p = mergeState.docValuesProducers[i];
            if (p == null) {
                producers[i] = null;
                continue;
            }

            // Pruning wrapper (recovery-source / _seq_no dropping): must be inspected BEFORE the generic FilterDocValuesProducer unwrap,
            // because PruningDocValuesProducer IS a FilterDocValuesProducer and unwrapping first would hide the pruning decision.
            if (p instanceof PruningMergePolicy.PruningDocValuesProducer pdv) {
                if (pdv.shouldPruneNumericDocValues(mergeFieldInfo.name)) {
                    return false; // field is being pruned; fall back
                }
            }

            // Generic ES filter wrapper delegating to an inner producer: peel it off to reach that inner producer.
            if (p instanceof FilterDocValuesProducer fdv) {
                p = fdv.getIn();
            }

            // Per-field reader: doc values are split by field, so ask it for the specific producer backing THIS field. A null result means
            // this field is handled by a different (non-columnar) format in that segment, which still leaves it with no columnar data here.
            if (p instanceof XPerFieldDocValuesFormat.FieldsReader pfr) {
                p = pfr.getDocValuesProducer(fi);
                if (p == null) {
                    producers[i] = null;
                    continue;
                }
            }

            // After unwrapping, the producer must be ours for the fast path. Anything else (a foreign or older format) forces the fallback.
            if (p instanceof FlattenedDocValuesProducer fdvp) {
                producers[i] = fdvp;
            } else {
                return false; // unrecognized producer — fall back
            }
        }

        doColumnWiseMerge(mergeFieldInfo, mergeState, producers);
        return true;
    }

    /**
     * Executes the column-wise merge: builds the merged key dictionary, performs the DISI presence pass, emits one column per merged key
     * directly into the data output, and writes the DISI, key dictionary, address table, and meta fields.
     *
     * @param producers per-segment {@link FlattenedDocValuesProducer} instances (null for segments that lack the field)
     */
    private void doColumnWiseMerge(FieldInfo mergeFieldInfo, MergeState mergeState, FlattenedDocValuesProducer[] producers)
        throws IOException {
        meta.writeInt(mergeFieldInfo.number);
        meta.writeByte(FLATTENED_COLUMNAR_BINARY);
        final long dataOffset = data.getFilePointer();
        meta.writeLong(dataOffset);

        final int numSources = producers.length;

        // Resolve FieldMetadata for each segment (null when the segment lacks the field).
        final FlattenedDocValuesProducer.FieldMetadata[] fieldMetadata = new FlattenedDocValuesProducer.FieldMetadata[numSources];
        for (int i = 0; i < numSources; i++) {
            if (producers[i] != null) {
                fieldMetadata[i] = producers[i].entryFor(mergeFieldInfo.name);
            }
        }

        // 1. Presence pass: walk merged binary doc values without calling binaryValue().

        final DISIAccumulator disiAcc = new DISIAccumulator(state.directory, state.context, data, DEFAULT_DENSE_RANK_POWER);
        int numDocsWithField = 0;
        int prevDocId = -1;
        boolean isDense = true;
        try {
            final BinaryDocValues merged = getMergedBinaryDocValues(mergeFieldInfo, mergeState);
            for (int doc = merged.nextDoc(); doc != NO_MORE_DOCS; doc = merged.nextDoc()) {
                numDocsWithField++;
                if (doc != prevDocId + 1) isDense = false;
                prevDocId = doc;
                disiAcc.addDocId(doc);
            }
        } catch (Throwable t) {
            IOUtils.closeWhileHandlingException(disiAcc);
            throw t;
        }

        // B2. uild the merged lex-ordered key dictionary via a k-way merge.

        // Each segment's key dict is already in lex order (ordinal = lex rank).
        // For each merged key, record the source ordinal per segment (-1 if absent).
        final int[] keyCursors = new int[numSources]; // current key ordinal in each segment
        final List<byte[]> mergedKeys = new ArrayList<>();
        // srcOrdsByKey.get(mergedOrd)[segIdx] = source ordinal in that segment, or -1.
        final List<int[]> srcOrdsByKey = new ArrayList<>();

        while (true) {
            // Find the lex-minimum key across all segment cursors.
            byte[] minBuf = null;
            int minStart = 0, minLen = 0;
            for (int i = 0; i < numSources; i++) {
                if (fieldMetadata[i] == null || keyCursors[i] >= fieldMetadata[i].numKeys) continue;
                final int ks = fieldMetadata[i].keyOffsets[keyCursors[i]];
                final int kl = fieldMetadata[i].keyOffsets[keyCursors[i] + 1] - ks;
                if (minBuf == null
                    || Arrays.compareUnsigned(fieldMetadata[i].keyBytes, ks, ks + kl, minBuf, minStart, minStart + minLen) < 0) {
                    minBuf = fieldMetadata[i].keyBytes;
                    minStart = ks;
                    minLen = kl;
                }
            }
            if (minBuf == null) break; // all cursors exhausted

            final byte[] kCopy = Arrays.copyOfRange(minBuf, minStart, minStart + minLen);
            mergedKeys.add(kCopy);

            // Advance every cursor that equals the minimum key.
            final int[] srcOrds = new int[numSources];
            Arrays.fill(srcOrds, -1);
            for (int i = 0; i < numSources; i++) {
                if (fieldMetadata[i] == null || keyCursors[i] >= fieldMetadata[i].numKeys) continue;
                final int ks = fieldMetadata[i].keyOffsets[keyCursors[i]];
                final int kl = fieldMetadata[i].keyOffsets[keyCursors[i] + 1] - ks;
                if (Arrays.compareUnsigned(fieldMetadata[i].keyBytes, ks, ks + kl, kCopy, 0, kCopy.length) == 0) {
                    srcOrds[i] = keyCursors[i];
                    keyCursors[i]++;
                }
            }
            srcOrdsByKey.add(srcOrds);
        }

        final int numMergedKeys = mergedKeys.size();

        // 3. Emit one column per merged key, writing blocks straight into data.

        int maxUncompressedBlockLen = 0;
        int maxDocsPerBlockSeen = 0;
        final FieldBlockWriter.ColumnAddress[] addresses = new FieldBlockWriter.ColumnAddress[numMergedKeys];

        // Allocate one writer and reuse it across all merged keys via reset(). This avoids
        // allocating ~128 KiB of block-accumulation arrays and a ZSTD compressor per key.
        final FieldBlockWriter mergeWriter = new FieldBlockWriter(data, targetBlockBytes, maxDocsPerBlock, minCompressBytes);
        boolean firstMergedKey = true;

        for (int mergedOrd = 0; mergedOrd < numMergedKeys; mergedOrd++) {
            final int[] srcOrds = srcOrdsByKey.get(mergedOrd);

            if (firstMergedKey == false) {
                mergeWriter.reset(data);
            }
            firstMergedKey = false;

            // Build a DocIDMerger.Sub for each segment that has this key.
            final List<ColumnMergeSub> subs = new ArrayList<>();
            try {
                for (int i = 0; i < numSources; i++) {
                    if (srcOrds[i] < 0 || fieldMetadata[i] == null) continue;
                    final FlattenedDocValuesProducer.FieldMetadata fm = fieldMetadata[i];
                    final int srcOrd = srcOrds[i];
                    final IndexInput dataIn = producers[i].cloneDataInput();
                    final SequentialColumnReader reader = new SequentialColumnReader(
                        dataIn,
                        fm.columnStartOffsets[srcOrd],
                        fm.blockIndexRelOffsets[srcOrd],
                        fm.numColumnBlocks[srcOrd]
                    );
                    subs.add(new ColumnMergeSub(mergeState.docMaps[i], reader));
                }

                // Write the column directly into the data output (no temp file, no splice).
                if (subs.isEmpty() == false) {
                    final DocIDMerger<ColumnMergeSub> merger = DocIDMerger.of(subs, mergeState.needsIndexSort);
                    ColumnMergeSub sub;
                    while ((sub = merger.next()) != null) {
                        final SequentialColumnReader reader = sub.reader;
                        mergeWriter.addDocSlots(
                            sub.mappedDocID,
                            reader.slotCount(),
                            reader.slotLens(),
                            reader.firstSlotIndex(),
                            reader.payload(),
                            reader.docSlotsOffset(),
                            reader.docSlotsLength()
                        );
                    }
                }
                mergeWriter.finish();
                maxUncompressedBlockLen = Math.max(maxUncompressedBlockLen, mergeWriter.maxUncompressedBlockLen);
                maxDocsPerBlockSeen = Math.max(maxDocsPerBlockSeen, mergeWriter.maxDocsPerBlockSeen);
                addresses[mergedOrd] = mergeWriter.columnAddress();

            } finally {
                IOUtils.close(subs);
            }
        }

        // 4. Write DISI (sparse doc presence).
        final DisiSection disi = writeDisiSection(disiAcc, numDocsWithField, isDense);
        IOUtils.close(disiAcc);

        // 5. Write key dictionary in merged lex order, summing key bytes so the reader can size its flat key pool in one allocation.
        final long keyDictOffset = data.getFilePointer();
        data.writeVInt(numMergedKeys);
        long totalKeyBytes = 0;
        for (byte[] kBytes : mergedKeys) {
            data.writeVInt(kBytes.length);
            data.writeBytes(kBytes, 0, kBytes.length);
            totalKeyBytes += kBytes.length;
        }
        final long keyDictLength = data.getFilePointer() - keyDictOffset;

        // 6. Write column address table.
        final long columnAddressTableOffset = data.getFilePointer();
        writeColumnAddressTable(addresses, numMergedKeys);

        final long dataLength = data.getFilePointer() - dataOffset;

        // 7. Write meta fields (must match the layout written by addBinaryField).
        writeFieldMeta(
            disi,
            numDocsWithField,
            numMergedKeys,
            keyDictOffset,
            keyDictLength,
            totalKeyBytes,
            columnAddressTableOffset,
            maxUncompressedBlockLen,
            maxDocsPerBlockSeen,
            dataLength
        );
    }

    /**
     * A {@link DocIDMerger.Sub} that drives a {@link SequentialColumnReader} and implements {@code Closeable} so {@link IOUtils#close} can
     * clean up all readers at once.
     */
    private static final class ColumnMergeSub extends DocIDMerger.Sub implements java.io.Closeable {
        final SequentialColumnReader reader;

        ColumnMergeSub(MergeState.DocMap docMap, SequentialColumnReader reader) {
            super(docMap);
            this.reader = reader;
        }

        @Override
        public int nextDoc() throws IOException {
            return reader.nextDoc();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /**
     * Writes the segment key dictionary to the data file. Keys are stored in lexicographic order; ordinal = lex rank, enabling direct
     * binary-search lookup without a sorted-ordinals side array.
     *
     * <pre>
     * vint numKeys
     * per key in lex order (ordinal = lex rank):
     *   vint keyLen
     *   keyLen bytes
     * </pre>
     * Returns the summed length of all key bytes, which the caller records in the meta file so the reader can size its flat key pool in
     * one allocation.
     */
    private long writeKeyDictionary(BytesRefHash keyHash, int numKeys, int[] sortedOrds) throws IOException {
        // Header: key count, so the reader can validate it against the count already recorded in the meta file.
        data.writeVInt(numKeys);

        // Emit each key as (length, bytes) in lex order, so its position in the stream is its ordinal, summing key bytes as we go.
        final BytesRef scratch = new BytesRef();
        long totalKeyBytes = 0;
        for (int lexRank = 0; lexRank < numKeys; lexRank++) {
            keyHash.get(sortedOrds[lexRank], scratch);
            data.writeVInt(scratch.length);
            data.writeBytes(scratch.bytes, scratch.offset, scratch.length);
            totalKeyBytes += scratch.length;
        }
        return totalKeyBytes;
    }

    /** The four meta fields describing doc presence, shared by the add and merge paths. */
    private record DisiSection(long offset, long length, short jumpTableEntryCount, byte denseRankPower) {}

    /**
     * Writes the sparse doc-presence bitset (IndexedDISI) after all column data and returns the meta fields describing it. offset == -2
     * marks an empty field, -1 a dense field (no DISI written), otherwise the DISI is appended and its offset/length recorded. Does not
     * close {@code disiAcc}.
     */
    private DisiSection writeDisiSection(DISIAccumulator disiAcc, int numDocsWithField, boolean isDense) throws IOException {
        if (numDocsWithField == 0) {
            return new DisiSection(-2L, 0L, (short) -1, (byte) -1);
        } else if (numDocsWithField == maxDoc && isDense) {
            return new DisiSection(-1L, 0L, (short) -1, (byte) -1);
        } else {
            final long disiStart = data.getFilePointer();
            final short jumpTableEntryCount = disiAcc.build(data);
            final long length = data.getFilePointer() - disiStart;
            return new DisiSection(disiStart, length, jumpTableEntryCount, DEFAULT_DENSE_RANK_POWER);
        }
    }

    /** Writes the column address table: one fixed-width entry per key in lex-ordinal order, locating its column (null entry → zeros). */
    private void writeColumnAddressTable(FieldBlockWriter.ColumnAddress[] addresses, int numKeys) throws IOException {
        for (int lexRank = 0; lexRank < numKeys; lexRank++) {
            final FieldBlockWriter.ColumnAddress addr = addresses[lexRank];
            if (addr != null) {
                data.writeLong(addr.columnStartOffset());
                data.writeInt(addr.blockIndexRelativeOffset());
                data.writeInt(addr.numBlocks());
            } else {
                data.writeLong(0L);
                data.writeInt(0);
                data.writeInt(0);
            }
        }
    }

    /** Writes the per-field meta record (must match the layout documented on the class and read by the producer). */
    private void writeFieldMeta(
        DisiSection disi,
        int numDocsWithField,
        int numKeys,
        long keyDictOffset,
        long keyDictLength,
        long totalKeyBytes,
        long columnAddressTableOffset,
        int maxUncompressedBlockLen,
        int maxDocsPerBlockSeen,
        long dataLength
    ) throws IOException {
        meta.writeLong(disi.offset());
        meta.writeLong(disi.length());
        meta.writeShort(disi.jumpTableEntryCount());
        meta.writeByte(disi.denseRankPower());
        meta.writeInt(numDocsWithField);
        meta.writeInt(numKeys);
        meta.writeLong(keyDictOffset);
        meta.writeLong(keyDictLength);
        meta.writeVLong(totalKeyBytes);
        meta.writeLong(columnAddressTableOffset);
        meta.writeVInt(maxUncompressedBlockLen);
        meta.writeVInt(maxDocsPerBlockSeen);
        meta.writeLong(dataLength);
    }

    @Override
    public void close() throws IOException {
        boolean success = false;
        try {
            if (meta != null) {
                meta.writeInt(FIELD_EOF);
                CodecUtil.writeFooter(meta);
            }
            if (data != null) {
                CodecUtil.writeFooter(data);
            }
            success = true;
        } finally {
            if (success) {
                IOUtils.close(data, meta);
            } else {
                IOUtils.closeWhileHandlingException(data, meta);
            }
            data = meta = null;
        }
    }

    @Override
    public void addNumericField(FieldInfo field, DocValuesProducer valuesProducer) {
        throw unsupported(field, "NUMERIC");
    }

    @Override
    public void addSortedField(FieldInfo field, DocValuesProducer valuesProducer) {
        throw unsupported(field, "SORTED");
    }

    @Override
    public void addSortedNumericField(FieldInfo field, DocValuesProducer valuesProducer) {
        throw unsupported(field, "SORTED_NUMERIC");
    }

    @Override
    public void addSortedSetField(FieldInfo field, DocValuesProducer valuesProducer) {
        throw unsupported(field, "SORTED_SET");
    }

    private static UnsupportedOperationException unsupported(FieldInfo field, String type) {
        return new UnsupportedOperationException(
            "["
                + FlattenedDocValuesFormat.CODEC_NAME
                + "] only supports BINARY doc values for flattened keyed fields, got ["
                + type
                + "] for field ["
                + field.name
                + "]; this indicates a PerFieldFormatSupplier#getDocValuesFormatForField dispatch bug"
        );
    }
}
