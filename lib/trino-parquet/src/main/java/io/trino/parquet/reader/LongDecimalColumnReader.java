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
package io.trino.parquet.reader;

import io.airlift.slice.Slice;
import io.trino.parquet.RichColumnDescriptor;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Type;
import org.apache.parquet.io.ParquetDecodingException;
import org.apache.parquet.io.api.Binary;

import java.math.BigInteger;

import static io.trino.spi.type.DecimalConversions.longToLongCast;
import static io.trino.spi.type.DecimalConversions.longToShortCast;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class LongDecimalColumnReader
        extends PrimitiveColumnReader
{
    private final DecimalType parquetDecimalType;

    LongDecimalColumnReader(RichColumnDescriptor descriptor, DecimalType parquetDecimalType)
    {
        super(descriptor);
        this.parquetDecimalType = requireNonNull(parquetDecimalType, "parquetDecimalType is null");
    }

    @Override
    protected void readValue(BlockBuilder blockBuilder, Type trinoType)
    {
        if (!(trinoType instanceof DecimalType)) {
            throw new ParquetDecodingException(format("Unsupported Trino column type (%s) for Parquet column (%s)", trinoType, columnDescriptor));
        }

        DecimalType trinoDecimalType = (DecimalType) trinoType;

        Binary binary = valuesReader.readBytes();
        Slice value = Decimals.encodeUnscaledValue(new BigInteger(binary.getBytes()));

        if (trinoDecimalType.isShort()) {
            trinoType.writeLong(blockBuilder, longToShortCast(
                    value,
                    parquetDecimalType.getPrecision(),
                    parquetDecimalType.getScale(),
                    trinoDecimalType.getPrecision(),
                    trinoDecimalType.getScale()));
        }
        else {
            trinoType.writeSlice(blockBuilder, longToLongCast(
                    value,
                    parquetDecimalType.getPrecision(),
                    parquetDecimalType.getScale(),
                    trinoDecimalType.getPrecision(),
                    trinoDecimalType.getScale()));
        }
    }
}
