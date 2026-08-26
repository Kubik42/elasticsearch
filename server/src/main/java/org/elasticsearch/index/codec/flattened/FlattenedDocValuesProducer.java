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
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.codecs.lucene90.IndexedDISI;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.codec.zstd.ZstdCompressionMode;
import org.elasticsearch.index.mapper.BlockLoader;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesConsumer.FLATTENED_COLUMNAR_BINARY;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.FLAG_ALL_SINGLE_SLOT;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.FLAG_DOCS_CONTIGUOUS;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.FLAG_META_COMPRESSED;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.FLAG_NO_NULL_VALUES;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.FLAG_VALUES_COMPRESSED;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.VERSION_CURRENT;
import static org.elasticsearch.index.codec.flattened.FlattenedDocValuesFormat.VERSION_START;

/**
 * Doc values producer for the columnar flattened format.
 *
 * <p>See {@link FlattenedDocValuesFormat} for the full on-disk layout.
 *
 * <p>Reading one sub-field (via {@link ColumnarKeyedBinaryDocValues#advanceExactKey}) only accesses that key's column. Reading the full
 * blob (via {@link BinaryDocValues#binaryValue}) performs a lockstep walk over all columns in key-ordinal (lex) order.
 */
final class FlattenedDocValuesProducer extends DocValuesProducer {

    static final Decompressor DECOMPRESSOR = new ZstdCompressionMode(1).newDecompressor();

    private final IndexInput data;
    /** Per-field layout parsed from the meta file at open time, keyed by field name. */
    private final Map<String, FieldMetadata> fieldMetadata;

    FlattenedDocValuesProducer(SegmentReadState state, String dataCodec, String dataExtension, String metaCodec, String metaExtension)
        throws IOException {
        final String dataFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
        final String metaFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);

        this.fieldMetadata = new HashMap<>();
        boolean success = false;
        IndexInput dataIn = null;
        try {
            // Open data file and verify its header.
            dataIn = state.directory.openInput(dataFileName, state.context);
            CodecUtil.checkIndexHeader(dataIn, dataCodec, VERSION_START, VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);

            // Open meta file and verify its header. We don't need to keep the meta file open, so extract the metadata and close it.
            try (ChecksumIndexInput metaIn = state.directory.openChecksumInput(metaFileName)) {
                CodecUtil.checkIndexHeader(
                    metaIn,
                    metaCodec,
                    VERSION_START,
                    VERSION_CURRENT,
                    state.segmentInfo.getId(),
                    state.segmentSuffix
                );

                // Extract the metadata.
                Throwable priorE = null;
                try {
                    readFieldMetadata(metaIn, dataIn, state.fieldInfos);
                } catch (Throwable e) {
                    priorE = e;
                } finally {
                    CodecUtil.checkFooter(metaIn, priorE);
                }
            }

            // The data file is kept open since we'll be constantly reading it.
            this.data = dataIn;
            success = true;
        } finally {
            if (success == false) {
                IOUtils.closeWhileHandlingException(dataIn);
            }
        }
    }

    /** Copy constructor used by {@link #getMergeInstance()}. */
    private FlattenedDocValuesProducer(FlattenedDocValuesProducer orig) throws IOException {
        this.data = orig.data.clone();
        this.fieldMetadata = orig.fieldMetadata;
    }

    /**
     * Reads the per-field metadata from {@code meta}, populating {@link #fieldMetadata} with one {@link FieldMetadata} per flattened field. Each
     * record is a field number, a DV type byte, then the field's {@link FieldMetadata}; the loop ends at the {@code -1} field-number
     * sentinel. Offsets in each entry point into {@code data}, which is not read here. Throws {@link CorruptIndexException} on an unknown
     * field number or an unexpected type byte.
     */
    private void readFieldMetadata(ChecksumIndexInput meta, IndexInput data, FieldInfos fieldInfos) throws IOException {
        for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
            final FieldInfo info = fieldInfos.fieldInfo(fieldNumber);
            if (info == null) {
                throw new CorruptIndexException("invalid field number: " + fieldNumber, meta);
            }
            final byte type = meta.readByte();
            if (type != FLATTENED_COLUMNAR_BINARY) {
                throw new CorruptIndexException(
                    "unexpected DV type byte " + type + " for field " + info.name + "; expected " + FLATTENED_COLUMNAR_BINARY,
                    meta
                );
            }
            final FieldMetadata metadata = readSingleFieldMetadata(meta, data);
            fieldMetadata.put(info.name, metadata);
        }
    }

    private static FieldMetadata readSingleFieldMetadata(ChecksumIndexInput meta, IndexInput data) throws IOException {
        // Build the metadata container.
        final FieldMetadata fm = new FieldMetadata();
        fm.dataOffset = meta.readLong();
        fm.docsWithFieldOffset = meta.readLong();
        fm.docsWithFieldLength = meta.readLong();
        fm.jumpTableEntryCount = meta.readShort();
        fm.denseRankPower = meta.readByte();
        fm.numDocsWithField = meta.readInt();
        fm.numKeys = meta.readInt();
        fm.keyDictOffset = meta.readLong();
        fm.keyDictLength = meta.readLong();
        fm.totalKeyBytes = meta.readVLong();
        fm.columnAddressTableOffset = meta.readLong();
        fm.maxUncompressedBlockLen = meta.readVInt();
        fm.maxDocsPerBlock = meta.readVInt();
        fm.dataLength = meta.readLong();

        // Load key dictionary into memory (keys are in lex order; ordinal = lex rank).
        data.seek(fm.keyDictOffset);
        loadKeyDictionary(data, fm);

        // Load column address table into memory.
        data.seek(fm.columnAddressTableOffset);
        loadColumnAddressTable(data, fm);

        return fm;
    }

    /**
     * Reads the key dictionary (keys in lex order, ordinal = lex rank) from the data file.
     *
     * <pre>
     * vint numKeys
     * per key in lex order:
     *   vint keyLen
     *   keyLen bytes
     * </pre>
     */
    private static void loadKeyDictionary(IndexInput data, FieldMetadata fm) throws IOException {
        // The on-disk key count must match what the meta file already recorded; a mismatch means the two files disagree, ie. corruption.
        final int numKeysOnDisk = data.readVInt();
        if (numKeysOnDisk != fm.numKeys) {
            throw new CorruptIndexException("key dictionary numKeys mismatch: expected " + fm.numKeys + " but got " + numKeysOnDisk, data);
        }

        // No keys: leave an empty byte pool and a single-element offset array (offsets[0]=0), so lookups compute zero-length slices cleanly
        if (fm.numKeys == 0) {
            fm.keyBytes = new byte[0];
            fm.keyOffsets = new int[1];
            return;
        }

        // Read each key in lex ordinal order into a contiguous byte pool, recording where each one starts so a key can be sliced back out
        // as an (offset, length) pair. keyOffsets[ord] is the start of ordinal ord; keyOffsets[numKeys] is the total byte count.
        // We do this because a flattened field is unbounded in the number of sub-keys it can contain. Therefore, packing everything into
        // two arrays rather than one byte[] per key eliminates the storage overhead that comes from each byte[]'s header.
        fm.keyBytes = new byte[Math.toIntExact(fm.totalKeyBytes)];
        fm.keyOffsets = new int[fm.numKeys + 1];
        int offset = 0;
        for (int ord = 0; ord < fm.numKeys; ord++) {
            fm.keyOffsets[ord] = offset;
            final int keyLen = data.readVInt();
            data.readBytes(fm.keyBytes, offset, keyLen);
            offset += keyLen;
        }
        fm.keyOffsets[fm.numKeys] = offset;
    }

    /**
     * Reads the column address table (one 16-byte entry per key, in lex ordinal order) from the data file into three parallel arrays on
     * {@code fm}.
     */
    private static void loadColumnAddressTable(IndexInput data, FieldMetadata fm) throws IOException {
        final int n = fm.numKeys;

        fm.columnStartOffsets = new long[n];   // absolute file offset where this key's column data begins
        fm.blockIndexRelOffsets = new int[n];  // offset (relative to the column start) of that column's block index
        fm.numColumnBlocks = new int[n];       // number of value blocks in the column

        for (int ord = 0; ord < n; ord++) {
            fm.columnStartOffsets[ord] = data.readLong();
            fm.blockIndexRelOffsets[ord] = data.readInt();
            fm.numColumnBlocks[ord] = data.readInt();
        }
    }

    /**
     * Returns the {@link FieldMetadata} for {@code fieldName}, or {@code null} if this producer has no entry for that field (e.g. the field
     * was absent in this segment).
     */
    FieldMetadata entryFor(String fieldName) {
        return fieldMetadata.get(fieldName);
    }

    /**
     * Returns a clone of the data {@link IndexInput} for independent sequential reading. The caller is responsible for closing the returned
     * clone.
     */
    IndexInput cloneDataInput() {
        return data.clone();
    }

    @Override
    public BinaryDocValues getBinary(FieldInfo field) throws IOException {
        final FieldMetadata entry = fieldMetadata.get(field.name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown field: " + field.name);
        }
        if (entry.docsWithFieldOffset == -2L) {
            return emptyBinary();
        }
        final IndexInput dataIn = data.clone();
        if (entry.docsWithFieldOffset == -1L) {
            return new DenseFlattenedBinaryDocValues(entry, dataIn);
        } else {
            final IndexedDISI disi = new IndexedDISI(
                dataIn,
                entry.docsWithFieldOffset,
                entry.docsWithFieldLength,
                entry.jumpTableEntryCount,
                entry.denseRankPower,
                entry.numDocsWithField
            );
            return new SparseFlattenedBinaryDocValues(entry, dataIn, disi);
        }
    }

    @Override
    public void checkIntegrity() throws IOException {
        CodecUtil.checksumEntireFile(data);
    }

    @Override
    public DocValuesProducer getMergeInstance() {
        try {
            return new FlattenedDocValuesProducer(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clone data file for merge", e);
        }
    }

    @Override
    public org.apache.lucene.index.DocValuesSkipper getSkipper(FieldInfo field) throws IOException {
        return null;
    }

    @Override
    public void close() throws IOException {
        data.close();
    }

    @Override
    public NumericDocValues getNumeric(FieldInfo field) {
        throw unsupported(field, "NUMERIC");
    }

    @Override
    public SortedDocValues getSorted(FieldInfo field) {
        throw unsupported(field, "SORTED");
    }

    @Override
    public SortedNumericDocValues getSortedNumeric(FieldInfo field) {
        throw unsupported(field, "SORTED_NUMERIC");
    }

    @Override
    public SortedSetDocValues getSortedSet(FieldInfo field) {
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

    /**
     * Per-field metadata loaded from the meta file.
     */
    static final class FieldMetadata {
        // The start ffset, in the data file, of this field's column region.
        long dataOffset;
        // Total byte length of this field's region in the data file.
        long dataLength;

        // Location of the doc-presence structure (IndexedDISI) in the data file. Sentinel values: -2 = field has no docs (empty),
        // -1 = every doc has the field (dense, no DISI stored), any other value = absolute offset of the sparse DISI bitset.
        long docsWithFieldOffset;
        // Byte length of the sparse DISI bitset (only meaningful when docsWithFieldOffset is a real offset).
        long docsWithFieldLength;
        // Number of entries in the DISI jump table, used to skip forward quickly over the sparse doc-presence bitset.
        short jumpTableEntryCount;
        // Rank-power parameter of the dense-block rank structure inside the DISI (controls the space/speed of within-block rank lookups).
        byte denseRankPower;
        // Number of documents that actually contain this field (equals maxDoc when dense).
        int numDocsWithField;

        // Number of distinct sub-field keys (columns) stored for this field.
        int numKeys;
        // Absolute data-file offset of the key dictionary.
        long keyDictOffset;
        // Byte length of the key dictionary in the data file.
        long keyDictLength;
        // Summed length of all key bytes, used to size the flat key pool (keyBytes) in one allocation.
        long totalKeyBytes;
        // Absolute data-file offset of the column address table.
        long columnAddressTableOffset;
        // Largest uncompressed block size across all columns of this field; used to size decompression scratch buffers up front.
        int maxUncompressedBlockLen;
        // Largest number of documents packed into a single block across all columns; used to size per-block arrays up front.
        int maxDocsPerBlock;

        // Key dictionary (lex order; ordinal = lex rank).
        // All key bytes concatenated in lex-ordinal order (key ord occupies keyBytes[keyOffsets[ord]..keyOffsets[ord+1])).
        byte[] keyBytes;
        // Start offset of each key within keyBytes; length is numKeys + 1 with the final entry marking the end of the last key.
        int[] keyOffsets;

        // Column address table (loaded into memory for fast cursor construction).
        // Per key (indexed by lex ordinal): absolute data-file offset of that column's block 0.
        long[] columnStartOffsets;
        // Per key: byte offset of the column's block index, relative to its columnStartOffset.
        int[] blockIndexRelOffsets;
        // Per key: number of blocks that make up that column.
        int[] numColumnBlocks;
    }

    /**
     * Reads blocks from one column. Created lazily per key ordinal on first access.
     *
     * <p>The cursor maintains a block index (eagerly loaded: typically 1–10 entries), and a two-stage lazy load per block: the metadata
     * region (slot counts + value lengths) is decompressed in {@link #ensureMetaRegionLoaded}, and the raw value region is decompressed in {@link
     * #ensureValuesLoaded}. Doc-presence checks and block skipping never decompress anything — only the uncompressed docId array in the
     * block header is consulted. For {@code FLAG_ALL_SINGLE_SLOT} blocks, slot-count queries are also free (the count is always 1). Random
     * access within a block is O(1) once the metadata is loaded: {@link #slotStarts} and {@link #valueOffsets} are prefix-sum tables built
     * once per block.
     */
    static final class ColumnCursor {
        private final IndexInput dataIn;          // cloned; independent file position
        private final long columnStartOff;        // absolute data-file offset of block 0
        private final int numBlocks;

        // Block index (eagerly loaded, always in-memory).
        private final int[] firstDocIds;         // firstDocIds[b] = first docId in block b
        private final int[] blockRelOffsets;     // blockRelOffsets[b] = byte offset from columnStartOff

        // Loaded block header (populated by loadBlockHeader).
        private int loadedBlock = -1;
        private int numDocsInBlock;
        private int firstDocInBlock;
        private boolean contiguous;
        private boolean allSingleSlot;
        private boolean noNullValues;
        private boolean valueCompressed;
        private boolean metaCompressed;
        private int[] docIds;      // resolved docIds when !contiguous
        private long metaAbsOff;   // absolute file position of the metadata region
        private int metaLen;       // uncompressed byte length of the metadata region

        // Decompressed metadata region (lazy; populated by ensureMetaRegionLoaded).
        private int numSlotsInBlock;
        private int[] slotStarts; // slotStarts[i] = first slot index for doc i; slotStarts[numDocs] = numSlots
        private long valueRegionOffset;  // absolute file position of the value region (set after ensureMetaRegionLoaded)
        private int bitsPerLen;          // bit width of the packed value-length array
        private int metaLensOff;         // offset of the packed value-length array within metaScratch
        private byte[] metaScratch = new byte[64]; // decompressed metadata buffer; grown on demand
        private boolean metaLoaded;

        // Decompressed value region (lazy; populated by ensureValuesLoaded).
        private int[] valueOffsets; // prefix-sum: slot s occupies payload[valueOffsets[s]..valueOffsets[s+1])
        private int[] slotLens;     // resolved per-slot length; -1 = null
        private byte[] payload = new byte[256];
        private boolean valuesLoaded;

        // Cursor state within current block.
        private int slotsRemaining = 0;
        private int curSlot = 0;         // slot index of the next nextSlot() read

        // Reusable slot result; its bytes are re-pointed at the shared payload on each nextSlot() call.
        private final BytesRef slotResult = new BytesRef();

        ColumnCursor(IndexInput data, long columnStartOff, int blockIndexRelOff, int numBlocks) throws IOException {
            this.dataIn = data.clone(); // Returns the clone of the stream, so that ColumnCursor can independently read the underlying bytes
            this.columnStartOff = columnStartOff;
            this.numBlocks = numBlocks;
            this.firstDocIds = new int[numBlocks];
            this.blockRelOffsets = new int[numBlocks];
            // Eagerly load the block index (8 bytes per block, typically very small).
            this.dataIn.seek(columnStartOff + blockIndexRelOff);
            for (int b = 0; b < numBlocks; b++) {
                firstDocIds[b] = this.dataIn.readInt();
                blockRelOffsets[b] = this.dataIn.readInt();
            }
            this.docIds = new int[8];
            this.slotStarts = new int[9];
            this.valueOffsets = new int[9];
            this.slotLens = new int[8];
        }

        /**
         * Returns the index of the block that would contain the given docId: the last block whose first docId does not exceed docId.
         * Returns -1 when docId falls before the first block's starting docId. Uses binary search over the ascending firstDocIds array.
         */
        private int findBlockFor(int docId) {
            if (numBlocks == 0 || docId < firstDocIds[0]) return -1;
            int lo = 0, hi = numBlocks - 1;
            while (lo < hi) {
                final int mid = (lo + hi + 1) >>> 1;
                if (firstDocIds[mid] <= docId) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        /**
         * Loads the header of block {@code blockIdx}: flags, numDocs, the bit-packed docId-delta array, and the metadata region location.
         * After this call, {@link #advanceToDoc} can answer doc-presence checks using only the uncompressed docId array (no decompression).
         * For {@code FLAG_ALL_SINGLE_SLOT} blocks, slot counts are also free (always 1). For multi-slot blocks, {@link #ensureMetaRegionLoaded()}
         * must be called first.
         */
        private void loadBlockHeader(int blockIdx) throws IOException {
            // This block is already loaded: nothing to do.
            if (loadedBlock == blockIdx) return;

            // Switching blocks: invalidate the lazily-decoded meta/value state so it is reloaded for the new block on demand.
            loadedBlock = blockIdx;
            metaLoaded = false;
            valuesLoaded = false;
            slotsRemaining = 0;

            // Position at the start of this block's header within the column.
            dataIn.seek(columnStartOff + blockRelOffsets[blockIdx]);

            // Decode the flags byte into the per-block layout switches used by ensureMetaLoaded/ensureValuesLoaded.
            final byte flags = dataIn.readByte();
            contiguous = (flags & FLAG_DOCS_CONTIGUOUS) != 0;
            allSingleSlot = (flags & FLAG_ALL_SINGLE_SLOT) != 0;
            noNullValues = (flags & FLAG_NO_NULL_VALUES) != 0;
            valueCompressed = (flags & FLAG_VALUES_COMPRESSED) != 0;
            metaCompressed = (flags & FLAG_META_COMPRESSED) != 0;

            // Number of docs in this block, and the docId of its first doc.
            numDocsInBlock = dataIn.readVInt();
            firstDocInBlock = firstDocIds[blockIdx];

            // Non-contiguous block: the docIds have gaps, so read the bit-packed delta array and reconstruct absolute docIds.
            // Deltas are gap-minus-one (consecutive docs store 0), so each docId is prev + delta + 1. A contiguous block stores no array;
            // its docIds are simply firstDocInBlock, firstDocInBlock+1, ... and are computed on the fly.
            if (contiguous == false) {
                if (docIds.length < numDocsInBlock) docIds = new int[numDocsInBlock];
                docIds[0] = firstDocInBlock;
                final int bitsPerDelta = Byte.toUnsignedInt(dataIn.readByte());
                FlattenedDocValuesFormat.unpackInts(dataIn, docIds, 1, numDocsInBlock - 1, bitsPerDelta);
                for (int i = 1; i < numDocsInBlock; i++) {
                    docIds[i] = docIds[i - 1] + docIds[i] + 1;
                }
            }

            // Record the length and absolute position of the metadata region so ensureMetaLoaded can decode it lazily later.
            metaLen = dataIn.readVInt();
            metaAbsOff = dataIn.getFilePointer();
        }

        /**
         * Decompresses (or reads) the metadata region for the current block, if not already done. Note, this meta region isn't the same
         * as the metadata file (.dvm).
         */
        private void ensureMetaRegionLoaded() throws IOException {
            // Already loaded: nothing to do.
            if (metaLoaded) return;

            // Seek to the block's metadata region and decompress (or read raw). The value region begins immediately after it so save its
            // absolute position for ensureValuesLoaded.
            dataIn.seek(metaAbsOff);
            metaScratch = FlattenedDocValuesFormat.readMaybeCompressed(DECOMPRESSOR, dataIn, metaLen, metaCompressed, metaScratch);
            valueRegionOffset = dataIn.getFilePointer();

            int offset = 0;
            if (allSingleSlot) {
                // Every doc has exactly one slot: the slot count equals the doc count and no per-doc slot array is stored.
                numSlotsInBlock = numDocsInBlock;
            } else {
                // Unpack the bit-packed per-doc slot counts, then convert them in place into a prefix-sum table.
                // Example: a 4-doc block whose docs own 2, 0, 3, 1 slots respectively.
                //   after unpackInts, slotStarts = [2, 0, 3, 1, _]   (raw counts; slotStarts[numDocs] not yet set)
                //   after the loop,   slotStarts = [0, 2, 2, 5, 6]   (prefix sums; final entry = total slots = 6)
                // So doc 2 owns slots slotStarts[2]..slotStarts[3] = 2..5 (its 3 slots), and doc 1 owns 2..2 (empty, 0 slots).
                if (slotStarts.length < numDocsInBlock + 1) slotStarts = new int[numDocsInBlock + 1];
                final int bitsPerSlot = Byte.toUnsignedInt(metaScratch[offset++]);
                offset = FlattenedDocValuesFormat.unpackInts(metaScratch, offset, slotStarts, 0, numDocsInBlock, bitsPerSlot);
                int acc = 0;
                for (int i = 0; i < numDocsInBlock; i++) {
                    final int cnt = slotStarts[i];
                    slotStarts[i] = acc;
                    acc += cnt;
                }
                slotStarts[numDocsInBlock] = acc;
                numSlotsInBlock = acc;
            }

            // The value-length array follows the slot counts; record its bit width and offset so ensureValuesLoaded can unpack it later.
            bitsPerLen = Byte.toUnsignedInt(metaScratch[offset++]);
            metaLensOff = offset;

            metaLoaded = true;
        }

        /**
         * Loads the value-length array and decompresses (or reads) the raw value region for the current block, if not already done.
         */
        private void ensureValuesLoaded() throws IOException {
            // Already loaded: nothing to do.
            if (valuesLoaded) return;

            // Metadata must be decoded first: it supplies numSlotsInBlock, bitsPerLen, metaLensOff, and valueRegionOffset, all needed.
            ensureMetaRegionLoaded();

            if (slotLens.length < numSlotsInBlock) slotLens = new int[numSlotsInBlock];
            if (valueOffsets.length < numSlotsInBlock + 1) valueOffsets = new int[numSlotsInBlock + 1];

            // Step 1: reconstruct the value layout. The value region on disk stores only per-slot lengths, not offsets, so rebuild the
            // offset table from those lengths. This tells us where each slot sits.

            // Unpack the per-slot value lengths from the already-decoded metadata buffer (no seek needed).
            FlattenedDocValuesFormat.unpackInts(metaScratch, metaLensOff, slotLens, 0, numSlotsInBlock, bitsPerLen);

            // Resolve the raw lengths into final slot lengths and build the prefix-sum offset table (valueOffsets[s] = byte offset of slot
            // s in the value region, valueOffsets[numSlots] = total bytes). Null slots contribute no bytes to the region.
            int totalValueBytes = 0;
            if (noNullValues) {
                // No nulls in this block: each unpacked length is the value length verbatim.
                for (int s = 0; s < numSlotsInBlock; s++) {
                    valueOffsets[s] = totalValueBytes;
                    totalValueBytes += slotLens[s];
                }
            } else {
                // Lengths are stored +1 so that 0 encodes null: decode as len = enc - 1, and mark null slots with length -1.
                for (int s = 0; s < numSlotsInBlock; s++) {
                    valueOffsets[s] = totalValueBytes;
                    final int enc = slotLens[s];
                    if (enc == 0) {
                        slotLens[s] = -1; // null
                    } else {
                        final int len = enc - 1;
                        slotLens[s] = len;
                        totalValueBytes += len;
                    }
                }
            }
            valueOffsets[numSlotsInBlock] = totalValueBytes;

            // Step 2: load the actual values. Now that the layout is known, decompress (or read raw) the block's concatenated value bytes
            // into the payload buffer; nextSlot() can then slice this buffer using the step 1 offset table above.

            // Seek to the value region, which begins right after the metadata region (ensureMetaLoaded may have been a no-op on this call,
            // so the file pointer is not necessarily there already).
            dataIn.seek(valueRegionOffset);

            // Decompress (or read raw) the concatenated value bytes for the whole block into the payload buffer.
            if (payload.length < totalValueBytes) payload = new byte[totalValueBytes];
            if (valueCompressed) {
                final BytesRef decompRef = new BytesRef(payload, 0, totalValueBytes);
                DECOMPRESSOR.decompress(dataIn, totalValueBytes, 0, totalValueBytes, decompRef);
                payload = decompRef.bytes;
            } else {
                dataIn.readBytes(payload, 0, totalValueBytes);
            }

            valuesLoaded = true;
        }

        /**
         * Returns the index of {@code docId} within the current loaded block, or -1 if absent. Caller must have called {@link
         * #loadBlockHeader} first.
         */
        private int findDocInBlock(int docId) {
            // Contiguous block: docIds are firstDocInBlock, +1, +2, ... so the index is a subtraction and a bounds check.
            if (contiguous) {
                final int idx = docId - firstDocInBlock;
                return (idx >= 0 && idx < numDocsInBlock) ? idx : -1;
            }

            // Non-contiguous block: docIds has the reconstructed absolute docIds in ascending order, so binary search for docId.
            int lo = 0, hi = numDocsInBlock - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (docIds[mid] < docId) lo = mid + 1;
                else if (docIds[mid] > docId) hi = mid - 1;
                else return mid;
            }

            return -1;
        }

        /**
         * Positions this cursor on {@code docId}.
         *
         * <p>Doc-presence checks never decompress anything: they use only the uncompressed docId array (or arithmetic for contiguous
         * blocks). For {@code FLAG_ALL_SINGLE_SLOT} blocks, the slot count (always 1) is free. For multi-slot blocks, the metadata region
         * is decompressed lazily via {@link #ensureMetaRegionLoaded()}, which is at most once per block and is a prerequisite of {@link
         * #nextSlot()} anyway. Backwards movement is O(1) once the metadata for the target block is loaded.
         *
         * @return the slot count for this doc (1 if allSingleSlot), or 0 if not present
         */
        int advanceToDoc(int docId) throws IOException {
            // Locate the block that would hold docId; a negative result means docId falls before the column's first block: absent.
            final int blockIdx = findBlockFor(docId);
            if (blockIdx < 0) return 0;

            // Load that block's header (docId array, flags) so the doc can be located; no decompression happens here.
            loadBlockHeader(blockIdx);

            // Find docId's index within the block; -1 means the block exists but this doc is not present in this column (ie. this doc has
            // no value for this key).
            final int docIdx = findDocInBlock(docId);
            if (docIdx < 0) return 0;

            final int firstSlot;
            if (allSingleSlot) {
                // Single-slot block: doc i owns slot i, exactly one slot each, so no metadata decode is needed.
                firstSlot = docIdx;
                slotsRemaining = 1;
            } else {
                // Multi-slot block: decode the metadata (slot-count prefix sums) to find this doc's slot range.
                ensureMetaRegionLoaded();
                firstSlot = slotStarts[docIdx];
                slotsRemaining = slotStarts[docIdx + 1] - firstSlot;
            }

            // Position the cursor at the doc's first slot; nextSlot() will read forward from here.
            curSlot = firstSlot;
            return slotsRemaining;
        }

        /**
         * Returns the next slot value for the current doc, or {@code null} for a null slot. Returns {@code null} with {@link
         * BytesRef#length} == -1 when all slots are exhausted.
         */
        BytesRef nextSlot() throws IOException {
            // No slots left for the current doc.
            if (slotsRemaining <= 0) {
                slotResult.length = -1;
                return null;
            }

            slotsRemaining--;

            // Decompress the block's value bytes and length/offset tables on first read of this block.
            ensureValuesLoaded();

            // Look up this slot's length and byte offset within the block payload, then advance the cursor.
            final int len = slotLens[curSlot];
            final int off = valueOffsets[curSlot];
            curSlot++;

            // null slot
            if (len < 0) return null;

            // Point the reusable BytesRef directly at the shared payload; valid until the next nextSlot().
            slotResult.bytes = payload;
            slotResult.offset = off;
            slotResult.length = len;

            return slotResult;
        }
    }

    // ---------------------------------------------------------------------------------
    // ColumnarKeyedBinaryDocValues base class (dense and sparse share this).
    // ---------------------------------------------------------------------------------

    private abstract static class FlattenedBinaryDocValues extends ColumnarKeyedBinaryDocValues {

        protected final FieldMetadata fieldMetadata;
        protected final IndexInput dataIn;

        protected int currentDocId = -1;

        // One ColumnCursor per key ordinal, lazily allocated.
        private final ColumnCursor[] columnCursors;

        // Active key ordinal after advanceExactKey().
        private int activeKeyOrd = -1;

        // Lazily created batch reader; null until first keyColumnReader() call.
        private KeyColumnBatchReader batchReader;
        // Key ordinal for which batchReader was built; -1 if not yet initialized.
        private int batchReaderOrd = -1;

        // Output buffers for binaryValue().
        private byte[] bvBuf = new byte[256];
        private final BytesRef bvResult = new BytesRef();

        FlattenedBinaryDocValues(FieldMetadata fm, IndexInput dataIn) {
            this.fieldMetadata = fm;
            this.dataIn = dataIn;
            this.columnCursors = new ColumnCursor[fm.numKeys];
        }

        /** Returns (or lazily creates) the ColumnCursor for key ordinal {@code ord}. */
        private ColumnCursor cursor(int ord) throws IOException {
            ColumnCursor cursor = columnCursors[ord];
            if (cursor == null) {
                cursor = new ColumnCursor(dataIn, fieldMetadata.columnStartOffsets[ord], fieldMetadata.blockIndexRelOffsets[ord],
                                          fieldMetadata.numColumnBlocks[ord]);
                columnCursors[ord] = cursor;
            }
            return cursor;
        }

        @Override
        public int docID() {
            return currentDocId;
        }

        @Override
        public long cost() {
            return fieldMetadata.numDocsWithField;
        }

        /**
         * Binary search for {@code key} in the lex-ordered dictionary. Since ordinal = lex rank, the returned value is the ordinal
         * directly.
         */
        @Override
        public int lookupKeyOrdinal(BytesRef key) {
            int lo = 0, hi = fieldMetadata.numKeys - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final int keyStart = fieldMetadata.keyOffsets[mid];
                final int keyLen = fieldMetadata.keyOffsets[mid + 1] - keyStart;
                final int cmp = compareKey(key, fieldMetadata.keyBytes, keyStart, keyLen);
                if (cmp < 0) hi = mid - 1;
                else if (cmp > 0) lo = mid + 1;
                else return mid;
            }
            return -1;
        }

        private static int compareKey(BytesRef key, byte[] dictBytes, int dictStart, int dictLen) {
            return Arrays.compareUnsigned(key.bytes, key.offset, key.offset + key.length, dictBytes, dictStart, dictStart + dictLen);
        }

        @Override
        public int advanceExactKey(int keyOrdinal) throws IOException {
            if (keyOrdinal < 0 || keyOrdinal >= fieldMetadata.numKeys) {
                activeKeyOrd = -1;
                return 0;
            }
            activeKeyOrd = keyOrdinal;
            return cursor(keyOrdinal).advanceToDoc(currentDocId);
        }

        @Override
        public BytesRef nextKeyValue() throws IOException {
            if (activeKeyOrd < 0) return null;
            final BytesRef slot = columnCursors[activeKeyOrd].nextSlot();
            // slot.length == -1 signals exhaustion (per ColumnCursor contract).
            if (slot != null && slot.length == -1) return null;
            return slot;
        }

        @Override
        public BlockLoader.OptionalColumnAtATimeReader keyColumnReader(int keyOrdinal) throws IOException {
            if (keyOrdinal < 0 || keyOrdinal >= fieldMetadata.numKeys) {
                return null;
            }
            if (batchReaderOrd != keyOrdinal) {
                final SequentialColumnReader seqCursor = new SequentialColumnReader(
                        dataIn.clone(),
                        fieldMetadata.columnStartOffsets[keyOrdinal],
                        fieldMetadata.blockIndexRelOffsets[keyOrdinal],
                        fieldMetadata.numColumnBlocks[keyOrdinal]
                );
                batchReader = new KeyColumnBatchReader(seqCursor);
                batchReaderOrd = keyOrdinal;
            }
            return batchReader;
        }

        /**
         * Reconstructs the full per-doc {@code key\0value} blob on demand. The columnar format splits each doc's flattened entries into
         * per-key columns so a single sub-field can be read without touching the others, but the {@link BinaryDocValues} contract exposes
         * one blob per doc. Consumers that need the whole doc rather than one column go through this method. It walks all columns in
         * ordinal (lex) order and concatenates their slots, so the result is not byte-identical to the row format when the original JSON
         * key order differed from lex order.
         */
        @Override
        public BytesRef binaryValue() throws IOException {
            final int docId = currentDocId;
            int off = 0;

            // Lockstep walk over every key column in ordinal (lex) order, concatenating each key's slots into one blob for this doc.
            for (int ord = 0; ord < fieldMetadata.numKeys; ord++) {
                // Position this column on the current doc; slotCount is how many slots (incl. nulls) the doc has for this key.
                final int slotCount = cursor(ord).advanceToDoc(docId);
                if (slotCount == 0) continue; // Doc has no entry for this key: nothing to emit.

                // Locate this key's bytes in the packed dictionary; reused for every slot of this key below.
                final int keyStart = fieldMetadata.keyOffsets[ord];
                final int keyLen = fieldMetadata.keyOffsets[ord + 1] - keyStart;

                for (int s = 0; s < slotCount; s++) {
                    // Pull the next slot value in document order; null means a null slot (KeyedArrayOrderInlineNull.recordNull).
                    final BytesRef slot = columnCursors[ord].nextSlot();
                    final boolean isNull = (slot == null);
                    final int valLen = isNull ? 0 : slot.length;
                    // Length prefix: 0 is the null sentinel, otherwise valLen+1 so the reader recovers valLen as prefix-1.
                    final int prefix = isNull ? 0 : valLen + 1;

                    // Ensure room for: worst-case 5-byte vInt + key + separator + value.
                    bvBuf = ArrayUtil.grow(bvBuf, off + 5 + keyLen + 1 + valLen);
                    off = writeVInt(bvBuf, off, prefix);

                    // Emit the entry as key\0value (value omitted for null slots).
                    System.arraycopy(fieldMetadata.keyBytes, keyStart, bvBuf, off, keyLen);
                    off += keyLen;
                    bvBuf[off++] = 0; // \0 separator
                    if (isNull == false && valLen > 0) {
                        System.arraycopy(slot.bytes, slot.offset, bvBuf, off, valLen);
                        off += valLen;
                    }
                }
            }

            // Point the reusable BytesRef at the freshly built blob; valid only until the next binaryValue() call.
            bvResult.bytes = bvBuf;
            bvResult.offset = 0;
            bvResult.length = off;
            return bvResult;
        }

        private static int writeVInt(byte[] buf, int off, int v) {
            while ((v & ~0x7F) != 0) {
                buf[off++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            buf[off++] = (byte) v;
            return off;
        }
    }

    // Dense and sparse differ only in doc-ID iteration; everything else (column cursors, blob assembly) lives in the shared base. Dense
    // (every doc has the field) iterates with a plain counter and no I/O; sparse consults an IndexedDISI bitset to skip absent docs.

    private static final class DenseFlattenedBinaryDocValues extends FlattenedBinaryDocValues {

        private int nextDocId = 0;
        private final int maxDocId;

        DenseFlattenedBinaryDocValues(FieldMetadata entry, IndexInput dataIn) {
            super(entry, dataIn);
            this.maxDocId = entry.numDocsWithField; // = maxDoc for dense
        }

        @Override
        public int nextDoc() throws IOException {
            if (nextDocId >= maxDocId) {
                currentDocId = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            currentDocId = nextDocId++;
            return currentDocId;
        }

        @Override
        public int advance(int target) throws IOException {
            if (target >= maxDocId) {
                currentDocId = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            currentDocId = target;
            nextDocId = target + 1;
            return currentDocId;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            currentDocId = target;
            if (target >= maxDocId) return false;
            nextDocId = target + 1;
            return true;
        }
    }

    private static final class SparseFlattenedBinaryDocValues extends FlattenedBinaryDocValues {

        private final IndexedDISI disi;

        SparseFlattenedBinaryDocValues(FieldMetadata entry, IndexInput dataIn, IndexedDISI disi) {
            super(entry, dataIn);
            this.disi = disi;
        }

        @Override
        public int nextDoc() throws IOException {
            final int doc = disi.nextDoc();
            currentDocId = doc;
            return doc;
        }

        @Override
        public int advance(int target) throws IOException {
            final int doc = disi.advance(target);
            currentDocId = doc;
            return doc;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            currentDocId = target;
            return disi.advanceExact(target);
        }
    }

    private static BinaryDocValues emptyBinary() {
        return new BinaryDocValues() {
            @Override
            public int nextDoc() {
                return NO_MORE_DOCS;
            }

            @Override
            public int docID() {
                return NO_MORE_DOCS;
            }

            @Override
            public long cost() {
                return 0;
            }

            @Override
            public int advance(int target) {
                return NO_MORE_DOCS;
            }

            @Override
            public boolean advanceExact(int target) {
                return false;
            }

            @Override
            public BytesRef binaryValue() {
                throw new IllegalStateException("advanceExact was false");
            }
        };
    }
}
