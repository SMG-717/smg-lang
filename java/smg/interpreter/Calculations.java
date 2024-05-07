package smg.interpreter;

import static smg.interpreter.Types.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Calculations {
    static Object calcUnary(Interpreter intr, UnaryOp op, Object value) {
        switch (op) {
            case Not: return !(Boolean) castValue(intr, "boolean", value);
            case Negate: {
                if (of(value, Double.class)) return - (double) value;
                else if (of(value, Long.class)) return - (long) value;
                break;
            }
            default:
        }
        throw intr.error(String.format(
            "Invalid unary operation '%s' on value of type %s", 
            op, javaType(value)
        ));
    }

    static Object calcAssign(
        Interpreter intr, AssignOp op, Object lhs, Object rhs
    ) {
        switch (op) {
            case AssignEqual: return rhs;
            case AddEqual: 
                return calcBinary(intr, BinaryOp.Add, lhs, rhs);
            case SubEqual: 
                return calcBinary(intr, BinaryOp.Subtract, lhs, rhs);
            case MultiplyEqual: 
                return calcBinary(intr, BinaryOp.Multiply, lhs, rhs);
            case DivideEqual: 
                return calcBinary(intr, BinaryOp.Divide, lhs, rhs);
            case ModEqual: 
                return calcBinary(intr, BinaryOp.Modulo, lhs, rhs);
            case AndEqual: 
                return calcBinary(intr, BinaryOp.And, lhs, rhs);
            case OrEqual: 
                return calcBinary(intr, BinaryOp.Or, lhs, rhs);
        }
        throw intr.error("Unsupported assignment operation: " + op);
    }

    static Object calcBinaryDouble(
        Interpreter intr, BinaryOp op, double lhs, double rhs
    ) {
        switch (op) {            
            case Exponent:          return Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            default:
        }
        throw intr.error(String.format("Invalid double operation %s", op));
    }

    static Object calcBinaryLong(
        Interpreter intr, BinaryOp op, long lhs, long rhs
    ) {
        switch (op) {
            case Exponent:          return (long) Math.pow(lhs, rhs);
            case Multiply:          return lhs * rhs;
            case Divide:            return lhs / rhs;
            case Modulo:            return lhs % rhs;
            case Add:               return lhs + rhs;
            case Subtract:          return lhs - rhs;
            case Greater:           return lhs > rhs;
            case GreaterEqual:      return lhs >= rhs;
            case Less:              return lhs < rhs;
            case LessEqual:         return lhs <= rhs;
            case NotEqual:          return lhs != rhs;
            case Equal:             return lhs == rhs;
            case BitAnd:            return lhs & rhs;
            case BitOr:             return lhs | rhs;
            case BitXor:            return lhs ^ rhs;
            case ShiftLeft:         return lhs << rhs;
            case ShiftRight:        return lhs >> rhs;
            default:
        }
        throw intr.error(String.format("Invalid long operation %s", op));
    }

    // Very useful rsource: 
    // https://docs.oracle.com/javase/specs/jls/se7/html/jls-5.html
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Object calcBinary(
        Interpreter intr, BinaryOp op, Object lhs, Object rhs
    ) {
        final RuntimeException invalidExpr = intr.error(
            "Invalid binary expression: (%s) %s (%s)", 
                javaType(lhs), op, javaType(rhs)
        );

        // 1. The operands are checked for nullness. If either of them are null,
        //    permit only the equality operations.
        if (lhs == null || rhs == null) {
            switch (op) {
                case Equal: return rhs == lhs;
                case NotEqual: return rhs != lhs;

                default: throw invalidExpr;
            }
        }
        
        // 2. If the operation is equality, and neither operand is a number, use
        //    the default java implementation to test it.
        if (!anyOf(Number.class, lhs, rhs)) {
            switch (op) {
                case Equal: return lhs.equals(rhs);
                case NotEqual: return !lhs.equals(rhs);
                default:
            }
        }

        // 3. Additionally, allow boolean arithmetic operations on any values.
        //    Non-null and non-zero objects are considered truthy with some 
        //    exceptions. Read the castValue function for more details. 
        switch (op) {
            case And: 
                return (Boolean) castValue(intr, "boolean", lhs) &&
                    (Boolean) castValue(intr, "boolean", rhs);
            case Or: 
                return (Boolean) castValue(intr, "boolean", lhs) || 
                    (Boolean) castValue(intr, "boolean", rhs);
            default:
        }

        // 4. If the LHS is a string, allow only concatenation and formatting
        //    operations.
        if (ofAny(lhs, String.class)) {
            switch (op) {
                case Add: 
                    return ((String) lhs).concat(
                        castValue(intr, "string", rhs)
                    );
                case Modulo: 
                    return String.format((String) lhs, rhs);
                
                default: throw invalidExpr;
            }
        }
        
        // 5. If the LHS is a List, allow only the concatenation operation. If
        //    the RHS is another list, all of its elements are added to the 
        //    former. Otherwise, the RHS is added as a single element. This 
        //    operation only constructs a new list and does not modify the 
        //    operands.    
        if (ofAny(lhs, List.class)) {
            if (op != BinaryOp.Add) throw invalidExpr;
            
            final List nlhs = new ArrayList<>((List) lhs);
            if (ofAny(rhs, List.class)) nlhs.addAll((List) rhs);
            else nlhs.add(rhs);

            return nlhs;
        }
        
        // 6. If the LHS is a Map, allow only the concatenation operation. The
        //    RHS must be another map. The result is all the keys and values
        //    from the second map are added to the former. In the case that both
        //    maps have different values for the same key, the second map wins.
        else if (ofAny(lhs, Map.class)) {
            if (op != BinaryOp.Add || !ofAny(rhs, Map.class)) throw invalidExpr;

            final Map nlhs = new HashMap<>((Map) lhs);
            nlhs.putAll((Map) rhs);
            return nlhs;
        }

        // 7. If both operands are Dates, allow only comparison operations. If
        //    only one of the operands is a Date and the other is some kind of
        //    number, that date will be coerced into a numeric representation,
        //    specifically, the number of milliseconds since epoch.
        else if (allOf(Date.class, lhs, rhs)) {
            final Date dlhs = (Date) lhs, drhs = (Date) rhs; 
            switch (op) {
                case Greater: return dlhs.after(drhs);
                case GreaterEqual: return !dlhs.before(drhs);
                case Less: return dlhs.before(drhs);
                case LessEqual: return !dlhs.after(drhs);
                default: throw invalidExpr;
            }
        }
        
        // 8. If either operand is a double or float, both sides get cast into 
        //    double and result will also be double. If either operand could not
        //    be cast into a double, an error would be thrown.
        if (doublish(lhs, rhs)) {
            return calcBinaryDouble(intr, op, 
                castValue(intr, "double", lhs), 
                castValue(intr, "double", rhs)
            );
        }
        
        // 9. If either operand is a long or int, both sides get cast into long 
        //    and result will also be long. Unless one of the operands is a
        //    Character, in which case the expression results in a char value.
        //    If either operand could not be cast into a long, an error would be
        //    thrown.
        else if (longish(lhs, rhs)) {
            final Object result = calcBinaryLong(intr, op, 
                castValue(intr, "long", lhs), 
                castValue(intr, "long", rhs)
            );
            return (anyOf(Character.class, lhs, rhs)) ? 
                castValue(intr, "char", result) : result;
        }

        // 10. If none of the above apply, throw an error.
        throw invalidExpr;
    }
}
