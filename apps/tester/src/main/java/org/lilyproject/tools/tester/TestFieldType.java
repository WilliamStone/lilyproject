package org.lilyproject.tools.tester;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.naming.OperationNotSupportedException;

import org.codehaus.jackson.JsonNode;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.lilyproject.repository.api.*;
import org.lilyproject.testclientfw.Words;
import org.lilyproject.util.json.JsonUtil;

public class TestFieldType {
    private static final Random random = new Random();
    private final Repository repository;

    FieldType fieldType;
    private String linkedRecordTypeName;
    private String linkedRecordSource;
    private final JsonNode properties;

    public TestFieldType(FieldType fieldType, Repository repository, JsonNode properties) {
        this.fieldType = fieldType;
        this.repository = repository;
        this.properties = properties;
        if (properties != null) {
            this.linkedRecordTypeName = JsonUtil.getString(properties, "recordType", null);
            this.linkedRecordSource = JsonUtil.getString(properties, "recordSource", null);
        }
    }

    public FieldType getFieldType() {
        return fieldType;
    }
    
    public String getLinkedRecordTypeName(){
        return linkedRecordTypeName;
    }
    
    public String getLinkedRecordSource() {
        return linkedRecordSource;
    }
    
    public ActionResult generateValue(TestAction testAction) {
        return generateValue(testAction, fieldType.getValueType());
    }
    
    private ActionResult generateList(TestAction testAction, ValueType valueType) {
        int size = (int)Math.ceil(Math.random() * 2);
        List<Object> values = new ArrayList<Object>();
        long duration = 0;
        for (int i = 0; i < size; i++) {
            ActionResult result = generateValue(testAction, valueType);
            duration += result.duration;
            if (result.success) 
                values.add(result.object);
            else return new ActionResult(false, null, duration);
        }
        return new ActionResult(true, values, duration);
    }

    private ActionResult generatePath(TestAction testAction, ValueType valueType) {
            int size = (int)Math.ceil(Math.random() * 3);
            Object[] elements = new Object[size];
            long duration = 0;
            for (int i = 0; i < size; i++) {
                ActionResult result = generateValue(testAction, valueType);
                duration += result.duration;
                if (result.success)
                    elements[i] = result.object;
                else return new ActionResult(false, null, duration);
            }
            return new ActionResult(true, new HierarchyPath(elements), duration);
    }
    
    private ActionResult generateValue(TestAction testAction, ValueType valueType) {
        String name = valueType.getSimpleName();

        if (name.equals("LIST")) {
            return generateList(testAction, valueType.getNestedValueType());
        } else if (name.equals("PATH")) {
            return generatePath(testAction, valueType.getNestedValueType());
        } else if (name.equals("STRING")) {
            return new ActionResult(true, generateString(), 0);
        } else if (name.equals("INTEGER")) {
            return new ActionResult(true, generateInt(), 0);
        } else if (name.equals("LONG")) {
            return new ActionResult(true, generateLong(), 0);
        } else if (name.equals("BOOLEAN")) {
            return new ActionResult(true, generateBoolean(), 0);
        } else if (name.equals("DATE")) {
            return new ActionResult(true, generateLocalDate(), 0);
        } else if (name.equals("DATETIME")) {
            return new ActionResult(true, generateDateTime(), 0);
        } else if (name.equals("BLOB")) {
            return new ActionResult(true, generateBlob(), 0);
        } else if (name.equals("LINK")) {
            try {
                return testAction.linkFieldAction(this, null);
            } catch (OperationNotSupportedException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException("Unsupported primitive value type: " + name);
        }
    }

    private String generateString() {
        String value = null;
        // Default
        if (properties == null) { 
            value = Words.get(Words.WordList.BIG_LIST, (int)Math.floor(Math.random() * 100));
        } else {
            int wordCount = JsonUtil.getInt(properties, "wordCount", 1);
            String wordString = JsonUtil.getString(properties, "enum", null);
            if (wordString != null) {
                String[] words = wordString.split(",");
                StringBuffer stringBuffer = new StringBuffer(20 * wordCount);
                for (int i = 0; i < wordCount; i++) {
                    if (i > 0)
                        stringBuffer.append(' ');
                    int index = (int) (Math.random() * words.length);
                    stringBuffer.append(words[index]);
                }
                value = stringBuffer.toString();
            } else {
                value = Words.get(Words.WordList.BIG_LIST,wordCount);
            }
        }
        return value;
    }
    
    private int generateInt() {
        // Default
        int value = 0;
        if (properties == null) {
            value = random.nextInt();
        } else {
            String numberString = JsonUtil.getString(properties, "enum", null);
            if (numberString != null) {
                String[] numbers = numberString.split(",");
                int index = (int) (Math.random() * numbers.length);
                value = Integer.valueOf(numbers[index]);
            } else {
                int min = JsonUtil.getInt(properties, "min", Integer.MIN_VALUE);
                int max = JsonUtil.getInt(properties, "max", Integer.MAX_VALUE);
                value = min + (int)(Math.random() * ((max - min) + 1));
            }
        }
        return value;
    }
    
    private long generateLong() {
        // Default
        long value = 0;
        if (properties == null)
            value = random.nextLong();
        else {
            String numberString = JsonUtil.getString(properties, "enum", null);
            if (numberString != null) {
                String[] numbers = numberString.split(",");
                int index = (int) (Math.random() * numbers.length);
                value = Long.valueOf(numbers[index]);
            } else {
                long min = JsonUtil.getLong(properties, "min", Long.MIN_VALUE);
                long max = JsonUtil.getLong(properties, "max", Long.MAX_VALUE);
                value = min + (long)(Math.random() * ((max - min) + 1));
            }
        }
        return value;
    }
    
    private boolean generateBoolean() {
        return random.nextBoolean();
    }
    
    private LocalDate generateLocalDate() {
        int year = 1950 + (int)(Math.random() * 100);
        int month = (int)Math.ceil(Math.random() * 12);
        int day = (int)Math.ceil(Math.random() * 25);
        return new LocalDate(year, month, day);
    }
    
    private DateTime generateDateTime() {
        int fail = 0;
        while (true) {
            int year = 1950 + (int)(Math.random() * 100);
            int month = (int)Math.ceil(Math.random() * 12);
            int day = (int)Math.ceil(Math.random() * 25);
            int hour = (int)Math.floor(Math.random() * 24);
            int minute = (int)Math.floor(Math.random() * 60);
            int second = (int)Math.floor(Math.random() * 60);
            try {
                return new DateTime(year, month, day, hour, minute, second, 0);
            } catch (IllegalArgumentException e) {
                // We can get exceptions here of the kind:
                //  "Illegal instant due to time zone offset transition"
                // This can occur if we happen to generate a time which falls in daylight
                // saving.
                if (fail > 10) {
                    throw new RuntimeException("Strange: did not succeed to generate a valid date after "
                            + fail + " tries.", e);
                }
                fail++;
            }
        }
    }
    
    private Blob generateBlob() {
        // Generate a blob that should be cleaned up by BlobIncubator
        actualGenerateBlob();
        return actualGenerateBlob();
    }
    
    private Blob actualGenerateBlob() {
        
        //4K, 150K, 200MB
        int[] sizes = new int[]{
                4000, 150000, 200000000
        };
//        long start = System.currentTimeMillis();
        int min = 1;
        int max = sizes[0 + (int)(Math.random() * ((2 - 0) + 1))];
        int size = min + (int)(Math.random() * ((max - min) + 1));
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        Blob blob = new Blob("tester", (long)bytes.length, "test"+size);
        try {
            OutputStream outputStream = repository.getOutputStream(blob);
            outputStream.write(bytes);
            outputStream.close();
//            System.out.println("created blob of size "+size+" in "+ (System.currentTimeMillis()-start) +" ms");
            return blob;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate blob", e);
        }
        
    }
    
    public Link generateLink() {
        return new Link(repository.getIdGenerator().newRecordId());
    }
    
    public ActionResult updateValue(TestAction testAction, Record record) {
        if (linkedRecordTypeName == null && linkedRecordSource == null)  {
            return generateValue(null); // The value will not be a link field, so we can give null here
        } else {
            Object value = record.getField(fieldType.getName());
            return updateMultiValue(testAction, value);
        }
    }
    
    private ActionResult updateMultiValue(TestAction testAction, Object value) {
        if (fieldType.getValueType().isMultiValue()) {
            List<Object> values = (List<Object>)value;
            int index = (int) (Math.random() * values.size());
            ActionResult result = updateHierarchical(testAction, values.get(index));
            if (result.success && result.object != null) {
                values.add(index, result.object);
                return new ActionResult(true, values, result.duration);
            }
            return updateHierarchical(testAction, values.get(index));
        } else {
            return updateHierarchical(testAction, value);
        }
    }
    
    private ActionResult updateHierarchical(TestAction testAction, Object value) {
        if (fieldType.getValueType().isHierarchical()) {
            HierarchyPath path = (HierarchyPath)value;
            Object[] values = path.getElements();
            int index = (int) (Math.random() * values.length);
            // LinkedRecordTypeName should only be given in case of link fields
            ActionResult result = updateLink(testAction, (Link)values[index]);
            if (result.success && result.object != null) {
                values[index] = result.object;
                return new ActionResult(true, values, result.duration);
            }
            return result;
        } else {
            return updateLink(testAction, (Link)value);
        }
    }
    
    private ActionResult updateLink(TestAction testAction, Link link) {
        try {
            return testAction.linkFieldAction(this, link.getMasterRecordId());
        } catch (OperationNotSupportedException e) {
            return new ActionResult(false, null, 0);
        }
    }
}
