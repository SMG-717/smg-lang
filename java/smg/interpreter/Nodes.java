package smg.interpreter;

import java.util.List;
import java.util.stream.Collectors;

// MARK: NodeScope
class NodeProgram {
    final List<NodeStmt> stmts;
    NodeProgram(List<NodeStmt> s) { stmts = s; }
    public String toString() {
        return String.join("", 
            stmts.stream()
            .map(x -> x.toString() + ";\n")
            .collect(Collectors.toList()));
    }
}

class NodeScope {
    final List<NodeStmt> stmts;
    NodeScope(List<NodeStmt> s) { stmts = s; }
    public String toString() {
        return "{\n" + 
            Parser.indent(
                String.join("", 
                stmts.stream()
                .map(x -> x.toString() + ";\n")
                .collect(Collectors.toList())),
            2)
        + "\n}";
    }
}

// MARK: NodeStatement
enum AssignOp { 
    AssignEqual("="), AddEqual("+="), SubEqual("-="), 
    MultiplyEqual("*="), DivideEqual("/="), ModEqual("%="), 
    AndEqual("&="), OrEqual("|=");
    private final String value;
    private AssignOp(String v) { value = v; }
    public String toString() { return value; };
}

abstract class NodeStmt {
    static class If extends NodeStmt {
        final NodeExpr expr; final NodeScope succ, fail;
        If (NodeExpr e, NodeScope s, NodeScope f) { 
            expr = e; succ = s; fail = f; 
        }

        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("if (%s) %s", expr, succ, 
               fail == null ? "" : String.format("\nelse %s", fail)
            ); 
        } 
    }

    static class While extends NodeStmt {
        final NodeExpr expr; final NodeScope scope; 
        While(NodeExpr e, NodeScope s) { expr = e; scope = s; }
        public void host(Visitor v) { v.visit(this);  }
        public String toString() { 
            return String.format("while (%s) %s", expr, scope); 
        } 
    }

    static class ForEach extends NodeStmt {
        final String itr; final NodeTerm list; final NodeScope scope; 
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("for (%s in %s) %s", itr, list, scope); 
        } 
        ForEach(String i, NodeTerm l, NodeScope s) { 
            itr = i; list = l; scope = s; 
        }
    }

    static class For extends NodeStmt {
        final Declare init; 
        final NodeExpr cond; 
        final NodeStmt inc; 
        final NodeScope scope;
        public void host(Visitor v) { v.visit(this);  }
        public String toString() { 
            return String.format("for (%s;%s;%s) %s", init, cond, inc, scope); 
        }
        For(Declare d, NodeExpr c, NodeStmt i, NodeScope s) {
            init = d; cond = c; inc = i; scope = s; 
        }
    }

    static class Scope extends NodeStmt {
        final NodeScope scope;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return scope.toString(); } 
        Scope(NodeScope s) { scope = s; }
    }

    static class Declare extends NodeStmt {
        final String var; final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("let %s = %s", var, expr); 
        } 
        Declare(String q, NodeExpr e) { var = q; expr = e; }
    }
    
    static class Assign extends NodeStmt {
        final NodeTerm term; final AssignOp op; final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("%s %s %s", term, op, expr); 
        }
        Assign(AssignOp o, NodeTerm q, NodeExpr e) { 
            op = o; term = q; expr = e; 
        }
    }

    static class Expr extends NodeStmt {
        final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return expr.toString(); } 
        Expr(NodeExpr e) { expr = e; }
    }

    static class Return extends NodeStmt {
        final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return "return" + (expr == null ? "" : " " + expr);
        } 
        Return(NodeExpr e) { expr = e; }
    }
    
    static class Break extends NodeStmt {
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return "break"; } 
    }
    
    static class Continue extends NodeStmt {
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return "continue"; } 
    }

    static class Function extends NodeStmt {
        final String name;
        final List<NodeParam> params;
        final NodeScope body;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("function %s (%s) %s", 
                name,  
                String.join(", ", 
                    params.stream()
                    .map(p -> p.toString())
                    .collect(Collectors.toList())), 
                body); 
        } 
        Function(String e, List<NodeParam> a, NodeScope b) { 
            name = e; params = a; body = b; 
        }
    }

    static class TryCatch extends NodeStmt {
        final NodeScope _try, _catch, _finally;
        final String err;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("try %s%s%s",
                 _try, 
                _catch == null ? "" : String.format("\ncatch %s%s",
                    err == null ? "" : String.format("(%s) ", err), _catch    
                ),
                _finally == null ? "" : String.format("\nfinally %s", _finally)
            ); 
        } 
        TryCatch(NodeScope t, NodeScope c, String e, NodeScope f) { 
            err = e; _try = t; _catch = c; _finally = f; 
        }
    }
    
    abstract public String toString();
    abstract void host(Visitor visitor);
    interface Visitor {
        void visit(Assign assignment);
        void visit(Declare declaration);
        void visit(Expr expression);
        void visit(If statement);
        void visit(While loop);
        void visit(For loop);
        void visit(ForEach loop);
        void visit(Scope scope);
        void visit(Break statement);
        void visit(Continue statement);
        void visit(Return statement);
        void visit(Function definition);
        void visit(TryCatch block);
    }
}

class NodeParam {
    final String param; final NodeExpr _default;
    NodeParam(String p, NodeExpr e) { param = p; _default = e; }
    public String toString() { 
        return param + (_default == null ? "" : (" = " + _default)); 
    }
}

// MARK: NodeExpression
enum BinaryOp {
    Exponent("**"),
    Multiply("*"), Divide("/"), Modulo("%"),
    Add("+"), Subtract("-"),
    ShiftLeft("<<"), ShiftRight(">>"),
    Less("<"), LessEqual("<="), Greater(">"), GreaterEqual(">="),
    Equal("=="), NotEqual("!="),
    BitAnd("&"), BitOr("|"), BitXor("^"),
    And("and"), Or("or");
    
    private final String symbol;
    private BinaryOp(String s) { symbol = s; }
    public String toString() { return symbol; }
}

abstract class NodeExpr {
    public final int line;
    private NodeExpr(int ln) { line = ln;}

    public static final NodeExpr NULL = new NodeExpr.Term(NodeTerm.NULL, 0);
    static class Binary extends NodeExpr {
        final NodeTerm lhs, rhs; final BinaryOp op;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { 
            return String.format("%s %s %s", lhs, op, rhs); 
        }
        Binary(BinaryOp o, NodeTerm l, NodeTerm r, int ln) { 
            super(ln); op = o; lhs = l; rhs = r; 
        }
    }
    
    static class Term extends NodeExpr {
        final NodeTerm val;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return val.toString(); } 
        Term(NodeTerm v, int ln) { super(ln); val = v; }
    }

    static class Lambda extends NodeExpr {
        final List<NodeParam> params;
        final NodeScope body;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() {
            final String ps = String.join(", ", 
                params.stream()
                .map(p -> p.toString())
                .collect(Collectors.toList())
            );
            final String b;
            if (body != null && body.stmts.size() > 1) {
                b = String.valueOf(body.stmts.get(0));
            }
            else {
                b = String.valueOf(body);
            }
            return String.format("function (%s) %s", ps, b); 
        } 
        Lambda(List<NodeParam> a, NodeScope b, int ln) { 
            super(ln); params = a; body = b; 
        }
    }

    abstract public String toString();
    abstract <R> R host(Visitor v);
    interface Visitor {
        <R> R visit(Binary node);
        <R> R visit(Lambda function);
        <R> R visit(Term node);
    }
}

// MARK: NodeTerm
enum UnaryOp { 
    Increment("++"), Decrement("--"), 
    Negate("-"), Invert("~"), Not("!");

    private final String value;
    private UnaryOp(String v) { value = v; }
    public String toString() {
        return value;
    }
}

abstract class NodeTerm {
    public static final NodeTerm NULL = new NodeTerm.Literal<Void>(null);
    static class Expr extends NodeTerm {
        final NodeExpr expr;
        public Expr(NodeExpr e) { expr = e; }
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("(%s)", expr); }
    }

    static class ArrayLiteral extends NodeTerm {
        final List<NodeExpr> items;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(items); } 
        public ArrayLiteral(List<NodeExpr> i) { items = i; }
    }

    static class MapLiteral extends NodeTerm {
        final List<NodeMapEntry> items;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { 
            if (items.size() == 0) {
                return "{}";
            }
            return "{\n" + 
                Parser.indent(
                    String.join(", \n", 
                    items.stream()
                    .map(e -> e.toString())
                    .collect(Collectors.toList())),
                2)
            + "\n}"; 
        } 
        public MapLiteral(List<NodeMapEntry> i) { items = i; }
    }

    static class UnaryExpr extends NodeTerm {
        final UnaryOp op; final NodeTerm val;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s%s", op, val); }
        UnaryExpr(UnaryOp o, NodeTerm v) { op = o; val = v; }
    }

    static class ArrayAccess extends NodeTerm {
        final NodeTerm array; final NodeExpr index;
        public <R> R host(Visitor v) { return v.visit(this); } 
        public String toString() { 
            return String.format("%s[%s]", array, index); 
        } 
        public ArrayAccess(NodeTerm a, NodeExpr i) { array = a; index = i; }
    }
    
    static class Variable extends NodeTerm {
        public final String var;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return var; } 
        public Variable(String v) { this.var = v; }
    }
    
    static class PropAccess extends NodeTerm {
        final NodeTerm object; final String prop;
        public <R> R host(Visitor visitor) { return visitor.visit(this); }
        public String toString() { return object.toString() + "." + prop; } 
        public PropAccess(NodeTerm o, String p) { object = o; prop = p; }
    }

    static class Literal<T> extends NodeTerm {
        final T lit;
        @SuppressWarnings("unchecked")
        public T host(Visitor v) { return v.visit(this); }
        public String toString() { 
            if (lit instanceof String) {
                return "\"" + lit + "\"";
            }
            else {
                return String.valueOf(lit); 
            }
        }
        public Literal(T l) { lit = l; }
    }
    
    static class Call extends NodeTerm {
        final NodeTerm f; final List<NodeExpr> args;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { 
            return String.format("%s(%s)", f, String.join(", ", args.stream()
                .map(a -> a.toString())
                .collect(Collectors.toList())
            )); 
        } 
        public Call(NodeTerm t, List<NodeExpr> e) { f = t; args = e; }
    }

    static class Cast extends NodeTerm {
        final NodeTerm object; final NodeType type;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { 
            return String.format("%s as %s", object, type); 
        } 
        public Cast(NodeTerm o, NodeType t) { object = o; type = t; }
    }

    abstract public String toString();
    abstract <R> R host(Visitor term);
    interface Visitor {
        <R> R visit(Expr expr);
        <R> R visit(ArrayLiteral arr);
        <R> R visit(MapLiteral map);
        <R> R visit(UnaryExpr expr);
        <R> R visit(ArrayAccess acc);
        <R> R visit(Variable var);
        <R> R visit(PropAccess var);
        <R> R visit(Literal<?> lit);
        <R> R visit(Call call);
        <R> R visit(Cast cast);
    }
}

class NodeMapEntry {
    final String key; final NodeExpr value;
    NodeMapEntry(String p, NodeExpr e) { key = p; value = e; }
    public String toString() { 
        return key + ": " + String.valueOf(value); 
    }
}

class NodeType {
    final String type;
    public String toString() { return type; }
    NodeType(String t) { type = t; }
}
