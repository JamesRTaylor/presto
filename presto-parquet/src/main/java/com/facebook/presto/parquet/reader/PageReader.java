/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.parquet.reader;

import com.facebook.presto.parquet.DataPage;
import com.facebook.presto.parquet.DataPageV1;
import com.facebook.presto.parquet.DataPageV2;
import com.facebook.presto.parquet.DictionaryPage;
import io.airlift.slice.Slice;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.internal.column.columnindex.OffsetIndex;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static com.facebook.presto.parquet.ParquetCompressionUtils.decompress;
import static java.lang.Math.toIntExact;

public class PageReader
{
    private final CompressionCodecName codec;
    private final long valueCount;
    private final List<DataPage> compressedPages;
    private final DictionaryPage compressedDictionaryPage;
    private final OffsetIndex offsetIndex;
    private int pageIndex;

    public PageReader(CompressionCodecName codec,
            List<DataPage> compressedPages,
            DictionaryPage compressedDictionaryPage)
    {
        this(codec, compressedPages, compressedDictionaryPage, null);
    }

    public PageReader(CompressionCodecName codec,
                      List<DataPage> compressedPages,
                      DictionaryPage compressedDictionaryPage,
                      OffsetIndex offsetIndex)
    {
        this.codec = codec;
        this.compressedPages = new LinkedList<>(compressedPages);
        this.compressedDictionaryPage = compressedDictionaryPage;
        int count = 0;
        for (DataPage page : compressedPages) {
            count += page.getValueCount();
        }
        this.valueCount = count;
        this.offsetIndex = offsetIndex;
        this.pageIndex = 0;
    }

    public long getTotalValueCount()
    {
        return valueCount;
    }

    public DataPage readPage()
    {
        if (compressedPages.isEmpty()) {
            return null;
        }
        DataPage compressedPage = compressedPages.remove(0);
        try {
            long firstRowIndex = getFirstRowIndex(pageIndex++, offsetIndex);
            if (compressedPage instanceof DataPageV1) {
                DataPageV1 dataPageV1 = (DataPageV1) compressedPage;
                Slice slice = decompress(codec, dataPageV1.getSlice(), dataPageV1.getUncompressedSize());
                return new DataPageV1(
                        slice,
                        dataPageV1.getValueCount(),
                        dataPageV1.getUncompressedSize(),
                        firstRowIndex,
                        dataPageV1.getStatistics(),
                        dataPageV1.getRepetitionLevelEncoding(),
                        dataPageV1.getDefinitionLevelEncoding(),
                        dataPageV1.getValueEncoding());
            }
            else {
                DataPageV2 dataPageV2 = (DataPageV2) compressedPage;
                if (!dataPageV2.isCompressed()) {
                    return dataPageV2;
                }
                int uncompressedSize = toIntExact(dataPageV2.getUncompressedSize()
                        - dataPageV2.getDefinitionLevels().length()
                        - dataPageV2.getRepetitionLevels().length());
                Slice slice = decompress(codec, dataPageV2.getSlice(), uncompressedSize);
                return new DataPageV2(
                        dataPageV2.getRowCount(),
                        dataPageV2.getNullCount(),
                        dataPageV2.getValueCount(),
                        firstRowIndex,
                        dataPageV2.getRepetitionLevels(),
                        dataPageV2.getDefinitionLevels(),
                        dataPageV2.getDataEncoding(),
                        slice,
                        dataPageV2.getUncompressedSize(),
                        dataPageV2.getStatistics(),
                        false);
            }
        }
        catch (IOException e) {
            throw new RuntimeException("Could not decompress page", e);
        }
    }

    public DictionaryPage readDictionaryPage()
    {
        if (compressedDictionaryPage == null) {
            return null;
        }
        try {
            return new DictionaryPage(
                    decompress(codec, compressedDictionaryPage.getSlice(), compressedDictionaryPage.getUncompressedSize()),
                    compressedDictionaryPage.getDictionarySize(),
                    compressedDictionaryPage.getEncoding());
        }
        catch (IOException e) {
            throw new RuntimeException("Error reading dictionary page", e);
        }
    }

    /**
     * Cast value to a an int, or throw an exception
     * if there is an overflow.
     *
     * @param value a long to be casted to an int
     * @return an int that is == to value
     * @throws IllegalArgumentException if value can't be casted to an int
     * @deprecated replaced by {@link java.lang.Math#toIntExact(long)}
     */
    public static int checkedCast(long value)
    {
        int valueI = (int) value;
        if (valueI != value) {
            throw new IllegalArgumentException(String.format("Overflow casting %d to an int", value));
        }
        return valueI;
    }

    public static long getFirstRowIndex(int pageIndex, OffsetIndex offsetIndex)
    {
        return offsetIndex == null ? -1 : offsetIndex.getFirstRowIndex(pageIndex);
    }
}
