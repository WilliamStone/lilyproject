package org.lilycms.hbaseindex;

import org.apache.hadoop.hbase.util.Bytes;
import org.codehaus.jackson.node.ObjectNode;
import org.lilycms.util.ArgumentValidator;

import java.util.*;

/**
 * Index field for datetimes, dates, times.
 *
 * <p>The instant is stored in the index as a long, or in case there is only a time
 * component, as an integer.
 *
 * <p>This class accepts java.util.Date as date/time representation. It is up to the
 * user to make sure any timezone corrections have happened already.
 */
public class DateTimeIndexFieldDefinition  extends IndexFieldDefinition {
    public enum Precision {DATETIME, DATETIME_NOMILLIS, DATE, TIME, TIME_NOMILLIS}
    private Precision precision = Precision.DATETIME_NOMILLIS;

    public DateTimeIndexFieldDefinition(String name) {
        super(name, IndexValueType.DATETIME);
    }

    public DateTimeIndexFieldDefinition(String name, ObjectNode jsonObject) {
        this(name);

        if (jsonObject.get("precision") != null)
            this.precision = Precision.valueOf(jsonObject.get("precision").getTextValue());
    }

    public Precision getPrecision() {
        return precision;
    }

    public void setPrecision(Precision precision) {
        ArgumentValidator.notNull(precision, "precision");
        this.precision = precision;
    }

    @Override
    public int getLength() {
        switch (precision) {
            case TIME:
            case TIME_NOMILLIS:
                return Bytes.SIZEOF_INT;
            default:
                return Bytes.SIZEOF_LONG;
        }
    }

    @Override
    public int toBytes(byte[] bytes, int offset, Object value) {
        return toBytes(bytes, offset, value, true);
    }

    @Override
    public int toBytes(byte[] bytes, int offset, Object value, boolean fillFieldLength) {
        Date date = (Date)value;

        Calendar calendar = new GregorianCalendar();
        calendar.setTime(date);

        long result;

        switch (precision) {
            case DATETIME:
                result = calendar.getTimeInMillis();
                break;
            case DATETIME_NOMILLIS:
                calendar.set(Calendar.MILLISECOND, 0);
                result = calendar.getTimeInMillis();
                break;
            case DATE:
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                result = calendar.getTimeInMillis();
                break;
            case TIME:
            case TIME_NOMILLIS:
                int hours = calendar.get(Calendar.HOUR_OF_DAY);
                int minutes = calendar.get(Calendar.MINUTE);
                int seconds = calendar.get(Calendar.SECOND);
                int millis = precision == Precision.TIME ? calendar.get(Calendar.MILLISECOND) : 0;
                result = (hours * 60 * 60 * 1000) + (minutes * 60 * 1000) + (seconds * 1000) + millis;
                break;
            default:
                throw new RuntimeException("Unexpected precision: " + precision);
        }

        int nextOffset;

        switch (precision) {
            case TIME:
            case TIME_NOMILLIS:
                nextOffset = Bytes.putInt(bytes, offset, (int)result);
                break;
            default:
                nextOffset = Bytes.putLong(bytes, offset, result);
        }

        // To make the ints/longs sort correctly when comparing their binary
        // representations, we need to invert the sign bit
        bytes[offset] = (byte)(bytes[offset] ^ 0x80);
        return nextOffset;
    }

    @Override
    public ObjectNode toJson() {
        ObjectNode object = super.toJson();
        object.put("precision", precision.toString());
        return object;
    }
}

