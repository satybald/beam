/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.zetasql;

import com.google.protobuf.ByteString;
import com.google.zetasql.ArrayType;
import com.google.zetasql.StructType;
import com.google.zetasql.StructType.StructField;
import com.google.zetasql.Type;
import com.google.zetasql.TypeFactory;
import com.google.zetasql.Value;
import com.google.zetasql.ZetaSQLType.TypeKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.TimeType;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.logicaltypes.SqlTypes;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.type.SqlTypeName;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.math.LongMath;
import org.joda.time.Instant;

/**
 * Utility methods for ZetaSQL related operations.
 *
 * <p>Unsupported ZetaSQL types: INT32, UINT32, UINT64, FLOAT, ENUM, PROTO, GEOGRAPHY
 * TODO[BEAM-8630]: support ZetaSQL types: DATE, TIME, DATETIME
 */
@Internal
public final class ZetaSqlUtils {

  private static final long MICROS_PER_MILLI = 1000L;

  private ZetaSqlUtils() {}

  // Type conversion: ZetaSQL => Calcite
  public static SqlTypeName zetaSqlTypeToCalciteTypeName(TypeKind zetaSqlType) {
    switch (zetaSqlType) {
      case TYPE_INT64:
        return SqlTypeName.BIGINT;
      case TYPE_NUMERIC:
        return SqlTypeName.DECIMAL;
      case TYPE_DOUBLE:
        return SqlTypeName.DOUBLE;
      case TYPE_STRING:
        return SqlTypeName.VARCHAR;
      case TYPE_DATE:
        return SqlTypeName.DATE;
      case TYPE_TIMESTAMP:
        return SqlTypeName.TIMESTAMP;
      case TYPE_BOOL:
        return SqlTypeName.BOOLEAN;
      case TYPE_BYTES:
        return SqlTypeName.VARBINARY;
        // TODO[BEAM-9179] Add conversion code for ARRAY and ROW types
      default:
        throw new UnsupportedOperationException("Unknown ZetaSQL type: " + zetaSqlType.name());
    }
  }

  // Type conversion: Beam => ZetaSQL
  public static Type beamFieldTypeToZetaSqlType(FieldType fieldType) {
    switch (fieldType.getTypeName()) {
      case INT64:
        return TypeFactory.createSimpleType(TypeKind.TYPE_INT64);
      case DECIMAL:
        return TypeFactory.createSimpleType(TypeKind.TYPE_NUMERIC);
      case DOUBLE:
        return TypeFactory.createSimpleType(TypeKind.TYPE_DOUBLE);
      case STRING:
        return TypeFactory.createSimpleType(TypeKind.TYPE_STRING);
      case DATETIME:
        // TODO[BEAM-8630]: Mapping Timestamp to DATETIME results in some timezone/precision issues.
        //  Can we convert Timestamp to a LogicalType? Will it solve the problem?
        return TypeFactory.createSimpleType(TypeKind.TYPE_TIMESTAMP);
      case BOOLEAN:
        return TypeFactory.createSimpleType(TypeKind.TYPE_BOOL);
      case BYTES:
        return TypeFactory.createSimpleType(TypeKind.TYPE_BYTES);
      case ARRAY:
        return beamElementFieldTypeToZetaSqlArrayType(fieldType.getCollectionElementType());
      case ROW:
        return beamSchemaToZetaSqlStructType(fieldType.getRowSchema());
      case LOGICAL_TYPE:
        return beamLogicalTypeToZetaSqlType(fieldType.getLogicalType().getIdentifier());
      default:
        throw new UnsupportedOperationException(
            "Unknown Beam fieldType: " + fieldType.getTypeName());
    }
  }

  private static ArrayType beamElementFieldTypeToZetaSqlArrayType(FieldType elementFieldType) {
    return TypeFactory.createArrayType(beamFieldTypeToZetaSqlType(elementFieldType));
  }

  public static StructType beamSchemaToZetaSqlStructType(Schema schema) {
    return TypeFactory.createStructType(
        schema.getFields().stream()
            .map(ZetaSqlUtils::beamFieldToZetaSqlStructField)
            .collect(Collectors.toList()));
  }

  private static StructField beamFieldToZetaSqlStructField(Field field) {
    return new StructField(field.getName(), beamFieldTypeToZetaSqlType(field.getType()));
  }

  private static Type beamLogicalTypeToZetaSqlType(String identifier) {
    if (SqlTypes.DATE.getIdentifier().equals(identifier)) {
      return TypeFactory.createSimpleType(TypeKind.TYPE_DATE);
    } else if (TimeType.IDENTIFIER.equals(identifier)) {
      return TypeFactory.createSimpleType(TypeKind.TYPE_TIME);
    } else {
      throw new UnsupportedOperationException("Unknown Beam logical type: " + identifier);
    }
  }

  // Value conversion: Beam => ZetaSQL
  public static Value javaObjectToZetaSqlValue(Object object, FieldType fieldType) {
    if (object == null) {
      return Value.createNullValue(beamFieldTypeToZetaSqlType(fieldType));
    }
    switch (fieldType.getTypeName()) {
      case INT64:
        return Value.createInt64Value((Long) object);
        // TODO[BEAM-8630]: Value.createNumericValue() is broken due to a dependency issue
        // case DECIMAL:
        //   return Value.createNumericValue((BigDecimal) object);
      case DOUBLE:
        return Value.createDoubleValue((Double) object);
      case STRING:
        return Value.createStringValue((String) object);
      case DATETIME:
        return jodaInstantToZetaSqlTimestampValue((Instant) object);
      case BOOLEAN:
        return Value.createBoolValue((Boolean) object);
      case BYTES:
        return Value.createBytesValue(ByteString.copyFrom((byte[]) object));
      case ARRAY:
        return javaListToZetaSqlArrayValue(
            (List<Object>) object, fieldType.getCollectionElementType());
      case ROW:
        return beamRowToZetaSqlStructValue((Row) object, fieldType.getRowSchema());
      case LOGICAL_TYPE:
        return beamLogicalObjectToZetaSqlValue(fieldType.getLogicalType().getIdentifier(), object);
      default:
        throw new UnsupportedOperationException(
            "Unknown Beam fieldType: " + fieldType.getTypeName());
    }
  }

  private static Value jodaInstantToZetaSqlTimestampValue(Instant instant) {
    return javaLongToZetaSqlTimestampValue(instant.getMillis());
  }

  private static Value javaLongToZetaSqlTimestampValue(Long millis) {
    return Value.createTimestampValueFromUnixMicros(
        LongMath.checkedMultiply(millis, MICROS_PER_MILLI));
  }

  private static Value javaListToZetaSqlArrayValue(List<Object> elements, FieldType elementType) {
    List<Value> values =
        elements.stream()
            .map(e -> javaObjectToZetaSqlValue(e, elementType))
            .collect(Collectors.toList());
    return Value.createArrayValue(beamElementFieldTypeToZetaSqlArrayType(elementType), values);
  }

  public static Value beamRowToZetaSqlStructValue(Row row, Schema schema) {
    List<Value> values = new ArrayList<>(row.getFieldCount());

    for (int i = 0; i < row.getFieldCount(); i++) {
      values.add(
          javaObjectToZetaSqlValue(
              row.getBaseValue(i, Object.class), schema.getField(i).getType()));
    }
    return Value.createStructValue(beamSchemaToZetaSqlStructType(schema), values);
  }

  private static Value beamLogicalObjectToZetaSqlValue(String identifier, Object object) {
    if (SqlTypes.DATE.getIdentifier().equals(identifier)) {
      if (object instanceof Long) { // base type
        return Value.createDateValue(((Long) object).intValue());
      } else { // input type
        return Value.createDateValue((int) ((LocalDate) object).toEpochDay());
      }
    } else {
      throw new UnsupportedOperationException("Unknown Beam logical type: " + identifier);
    }
  }

  // Value conversion: ZetaSQL => Beam
  public static Object zetaSqlValueToJavaObject(
      Value value, FieldType fieldType, boolean verifyValues) {
    if (value.isNull()) {
      return null;
    }
    switch (fieldType.getTypeName()) {
      case INT64:
        return value.getInt64Value();
      case DECIMAL:
        return value.getNumericValue();
      case DOUBLE:
        // Floats with a floating part equal to zero are treated as whole (INT64).
        // Cast to double when that happens.
        if (value.getType().getKind().equals(TypeKind.TYPE_INT64)) {
          return (double) value.getInt64Value();
        }
        return value.getDoubleValue();
      case STRING:
        return value.getStringValue();
      case DATETIME:
        return zetaSqlTimestampValueToJodaInstant(value);
      case BOOLEAN:
        return value.getBoolValue();
      case BYTES:
        return value.getBytesValue().toByteArray();
      case ARRAY:
        return zetaSqlArrayValueToJavaList(
            value, fieldType.getCollectionElementType(), verifyValues);
      case ROW:
        return zetaSqlStructValueToBeamRow(value, fieldType.getRowSchema(), verifyValues);
      case LOGICAL_TYPE:
        return zetaSqlValueToBeamLogicalObject(fieldType.getLogicalType().getIdentifier(), value);
      default:
        throw new UnsupportedOperationException(
            "Unknown Beam fieldType: " + fieldType.getTypeName());
    }
  }

  private static Instant zetaSqlTimestampValueToJodaInstant(Value timestampValue) {
    long millis = timestampValue.getTimestampUnixMicros() / MICROS_PER_MILLI;
    return Instant.ofEpochMilli(millis);
  }

  private static List<Object> zetaSqlArrayValueToJavaList(
      Value arrayValue, FieldType elementType, boolean verifyValues) {
    return arrayValue.getElementList().stream()
        .map(e -> zetaSqlValueToJavaObject(e, elementType, verifyValues))
        .collect(Collectors.toList());
  }

  private static Row zetaSqlStructValueToBeamRow(
      Value structValue, Schema schema, boolean verifyValues) {
    List<Object> objects = new ArrayList<>(schema.getFieldCount());
    List<Value> values = structValue.getFieldList();
    for (int i = 0; i < values.size(); i++) {
      objects.add(
          zetaSqlValueToJavaObject(values.get(i), schema.getField(i).getType(), verifyValues));
    }
    Row row =
        verifyValues
            ? Row.withSchema(schema).addValues(objects).build()
            : Row.withSchema(schema).attachValues(objects);
    return row;
  }

  private static Object zetaSqlValueToBeamLogicalObject(String identifier, Value value) {
    if (SqlTypes.DATE.getIdentifier().equals(identifier)) {
      return LocalDate.ofEpochDay(value.getDateValue());
    } else {
      throw new UnsupportedOperationException("Unknown Beam logical type: " + identifier);
    }
  }
}