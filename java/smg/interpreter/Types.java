package smg.interpreter;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

class Types {
    static String javaType(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }

    static boolean ofAny(Object value, Class<?>... classes) {
        for (Class<?> c : classes) {
            if (c.isInstance(value)) return true;
        }
        return false;
    }

    static boolean anyOf(Class<?> c, Object... values) {
        for (Object value : values) {
            if (c.isInstance(value)) return true;
        }
        return false;
    }
    static boolean of(Object value, Class<?> c) {
        return c.isInstance(value);
    }

    static boolean allOf(Class<?> c, Object... values) {
        for (Object value : values) {
            if (!c.isInstance(value)) return false;
        }
        return true;
    }

    static boolean doublish(Object... values) {
        for (Object value : values) {
            if (ofAny(value, Double.class, Float.class, BigDecimal.class)) 
                return true;
        }
        return false;
    }
    
    static boolean longish(Object... values) {
        for (Object value : values) {
            if (ofAny(value, Long.class, Integer.class)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static <R> R castValue(Interpreter intr, String type, Object value) {
        // First Sweep
        switch (type) {
            // If the target is a string, it handed by Java default
            case "string": return (R) String.valueOf(value);

            // If the target type is a number, nulls, dates, chars, and bools
            // are all converted the same way
            case "int": case "double": case "long": case "float":
            case "boolean": // Experimental
            if (value == null) value = 0;
            else if (of(value, Date.class)) 
                value = ((Date) value).getTime(); 
            else if (of(value, Character.class)) 
                value = (int) ((Character) value).charValue();                
            else if (of(value, Boolean.class))
                value = (Boolean) value ? 1.0D : 0.0D;
        }

        // Second Sweep
        switch (type) {
            // Numbers
            case "int": 
            case "long": {
                if (of(value, Number.class)) 
                    return (R) (Long) ((Number) value).longValue();
                else if (of(value, String.class)) 
                    return (R) Long.valueOf((String) value);
                break;
            }
            
            case "float": 
            case "double": {
                if (of(value, Number.class)) 
                    return (R) (Double) ((Number) value).doubleValue();
                else if (of(value, String.class)) 
                    return (R) Double.valueOf((String) value);
                break;
            }
            
            // Boolean
            case "boolean": {
                if (of(value, Number.class)) 
                    value = ((Number) value).doubleValue() != 0.0D;
                else if (of(value, String.class)) 
                    value = !((String) value).isEmpty();
                else if (of(value, List.class)) 
                    value = ((List<?>) value).size() > 0;
                else if (of(value, Map.class)) 
                    value = ((Map<?, ?>) value).size() > 0;
                else value = true;

                return (R) (Boolean) value;
            }
            
            // Character
            case "char": {
                if (of(value, Number.class)) 
                return (R) (Character) (char) ((Number) value).intValue();
                else if (of(value, String.class)) {
                    final String s = (String) value;
                    if (s.isEmpty()) 
                        return (R) (Character) '\0';
                    else if (s.length() == 1) 
                        return (R) (Character) s.charAt(0);
                    throw intr.error(
                        "Cannot convert string of length 2 or more into char"
                    );
                }
                break;
            }

            // Date
            case "date": {
                if (value == null) return null;
                else if (of(value, Number.class)) 
                    return (R) Date.from(
                        Instant.ofEpochMilli(((Number) value).longValue())
                    );
                else if (of(value, String.class)) {
                    try {
                        return (R) DateFormat.getInstance()
                            .parse((String) value);
                    } catch (ParseException e) {
                        throw intr.error(
                            "Date parse error. '%s' is not recognised", value
                        );
                    }
                }
                break;
            }
        }

        throw intr.error(
            "Casting from %s to %s is not allowed.", javaType(value), type
        );
    }

}
