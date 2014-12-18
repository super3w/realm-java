package io.realm.processor;

import com.squareup.javawriter.JavaWriter;

import java.io.IOException;
import java.util.HashMap;

/**
 * Helper class for converting between Json types and data types in Java that are supported by Realm.
 */
public class RealmJsonTypeHelper {

    private static final HashMap<String, JsonToRealmTypeConverter> JAVA_TO_JSON_TYPES;

    static {
        JAVA_TO_JSON_TYPES = new HashMap<String, JsonToRealmTypeConverter>();

        JAVA_TO_JSON_TYPES.put("byte", new SimpleTypeConverter("byte", "Int"));
        JAVA_TO_JSON_TYPES.put("short", new SimpleTypeConverter("short", "Int"));
        JAVA_TO_JSON_TYPES.put("int", new SimpleTypeConverter("int", "Int"));
        JAVA_TO_JSON_TYPES.put("long", new SimpleTypeConverter("long", "Long"));
        JAVA_TO_JSON_TYPES.put("float", new SimpleTypeConverter("float", "Double"));
        JAVA_TO_JSON_TYPES.put("double", new SimpleTypeConverter("double", "Double"));
        JAVA_TO_JSON_TYPES.put("boolean", new SimpleTypeConverter("boolean", "Boolean"));
        JAVA_TO_JSON_TYPES.put("Byte", new SimpleTypeConverter("Byte", "Int"));
        JAVA_TO_JSON_TYPES.put("Short", new SimpleTypeConverter("Short", "Int"));
        JAVA_TO_JSON_TYPES.put("Integer", new SimpleTypeConverter("Integer", "Int"));
        JAVA_TO_JSON_TYPES.put("Long", new SimpleTypeConverter("Long", "Long"));
        JAVA_TO_JSON_TYPES.put("Float", new SimpleTypeConverter("Float", "Double"));
        JAVA_TO_JSON_TYPES.put("Double", new SimpleTypeConverter("Double", "Double"));
        JAVA_TO_JSON_TYPES.put("Boolean", new SimpleTypeConverter("Boolean", "Boolean"));
        JAVA_TO_JSON_TYPES.put("java.lang.String", new SimpleTypeConverter("String", "String"));
        JAVA_TO_JSON_TYPES.put("java.util.Date", new JsonToRealmTypeConverter() {
            @Override
            public void emitTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
                writer
                    .emitStatement("long timestamp = json.optLong(\"%s\", -1)", fieldName)
                    .beginControlFlow("if (timestamp > -1)")
                        .emitStatement("%s(new Date(timestamp))", setter)
                    .nextControlFlow("else")
                        .emitStatement("String jsonDate = json.getString(\"%s\")", fieldName)
                        .emitStatement("%s(JsonUtils.stringToDate(jsonDate))", setter)
                    .endControlFlow();
            }

            @Override
            public void emitStreamTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
                writer
                    .beginControlFlow("if (reader.peek() == JsonToken.NUMBER)")
                        .emitStatement("long timestamp = reader.nextLong()", fieldName)
                        .beginControlFlow("if (timestamp > -1)")
                            .emitStatement("%s(new Date(timestamp))", setter)
                        .endControlFlow()
                    .nextControlFlow("else")
                        .emitStatement("%s(JsonUtils.stringToDate(reader.nextString()))", setter)
                    .endControlFlow();
            }
        });

        JAVA_TO_JSON_TYPES.put("byte[]", new JsonToRealmTypeConverter() {
            @Override
            public void emitTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
                writer.emitStatement("%s(JsonUtils.stringToBytes(json.getString(\"%s\")))", setter, fieldName);
            }

            @Override
            public void emitStreamTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
                writer.emitStatement("%s(JsonUtils.stringToBytes(reader.nextString()))", setter);
            }
        });
    }

    public static void emitFillJavaTypeWithJsonValue(String setter, String fieldName, String qualifiedFieldType, JavaWriter writer) throws IOException {
        if (JAVA_TO_JSON_TYPES.containsKey(qualifiedFieldType)) {
            writer.beginControlFlow("if (json.has(\"%s\"))", fieldName);
                JAVA_TO_JSON_TYPES.get(qualifiedFieldType).emitTypeConversion(setter, fieldName, qualifiedFieldType, writer);
            writer.endControlFlow();
        }
    }

    public static void emitFillRealmObjectWithJsonValue(String setter, String fieldName, String qualifiedFieldType, JavaWriter writer) throws IOException {
        writer
            .beginControlFlow("if (json.has(\"%s\"))", fieldName)
                .emitStatement("%s obj = realm.createObject(%s.class)", qualifiedFieldType, qualifiedFieldType)
                .emitStatement("((RealmObject) obj).populateUsingJsonObject(json.getJSONObject(\"%s\"))", fieldName)
                .emitStatement("%s(obj)", setter)
            .endControlFlow();
    }

    public static void emitFillRealmListWithJsonValue(String getter, String fieldName, String fieldTypeCanonicalName, JavaWriter writer) throws IOException {
        writer
            .beginControlFlow("if (json.has(\"%s\"))", fieldName)
                .emitStatement("JSONArray array = json.getJSONArray(\"%s\")", fieldName)
                .beginControlFlow("for (int i = 0; i < array.length(); i++)")
                    .emitStatement("%s obj = realm.createObject(%s.class)", fieldTypeCanonicalName, fieldTypeCanonicalName)
                    .emitStatement("((RealmObject) obj).populateUsingJsonObject(array.getJSONObject(i))")
                    .emitStatement("%s().add(obj)", getter)
                .endControlFlow()
            .endControlFlow();
    }


    public static void emitFillJavaTypeFromStream(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
        if (JAVA_TO_JSON_TYPES.containsKey(fieldType)) {
            JAVA_TO_JSON_TYPES.get(fieldType).emitStreamTypeConversion(setter, fieldName, fieldType, writer);
        }
    }

    public static void emitFillRealmObjectFromStream(String setter, String fieldName, String fieldTypeCanonicalName, JavaWriter writer) throws IOException {
        writer
            .emitStatement("%s obj = realm.createObject(%s.class)", fieldTypeCanonicalName, fieldTypeCanonicalName)
            .emitStatement("((RealmObject) obj).populateUsingJsonStream(reader)", fieldName)
            .emitStatement("%s(obj)", setter);
    }

    public static void emitFillRealmListFromStream(String getter, String fieldTypeCanonicalName, JavaWriter writer) throws IOException {
        writer
            .emitStatement("reader.beginArray()")
            .beginControlFlow("while (reader.hasNext())")
                .emitStatement("%s obj = realm.createObject(%s.class)", fieldTypeCanonicalName, fieldTypeCanonicalName)
                .emitStatement("((RealmObject) obj).populateUsingJsonStream(reader)")
                .emitStatement("%s().add(obj)", getter)
            .endControlFlow()
            .emitStatement("reader.endArray()");
    }

    private static class SimpleTypeConverter implements JsonToRealmTypeConverter {

        private final String castType;
        private final String jsonType;

        /**
         * Create a conversion between simple types that can be expressed of the form
         * RealmObject.setFieldName((<castType>) json.get<jsonType>) or
         * RealmObject.setFieldName((<castType>) reader.next<jsonType>
         *
         * @param castType  Java type to cast to.
         * @param jsonType  JsonType to get data from.
         */
        private SimpleTypeConverter(String castType, String jsonType) {
            this.castType = castType;
            this.jsonType = jsonType;
        }

        @Override
        public void emitTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
            writer.emitStatement("%s((%s) json.get%s(\"%s\"))",
                    setter,
                    castType,
                    jsonType,
                    fieldName);
        }

        @Override
        public void emitStreamTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException {
            writer.emitStatement("%s((%s) reader.next%s())",
                    setter,
                    castType,
                    jsonType);
        }
    }

    private interface JsonToRealmTypeConverter {
        public void emitTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException;
        public void emitStreamTypeConversion(String setter, String fieldName, String fieldType, JavaWriter writer) throws IOException;
    }
}
