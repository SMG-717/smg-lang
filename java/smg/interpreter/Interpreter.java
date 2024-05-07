package smg.interpreter;

import static smg.interpreter.Types.*;
import static smg.interpreter.Calculations.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import smg.interpreter.Capture.F;
import smg.interpreter.Capture.F0;

public class Interpreter {

    // Jump instructions which can interrupt statement execution
    enum JumpOp { RETURN, BREAK, CONTINUE; }

    // Variables are stored in a list of scopes. There is at least one scope at
    // any one time during execution namely the global scope. Different 
    // variables can have the same name as long as they're stored in different
    // scopes. This is what is known in language design as 'shadowing'.
    private final LinkedList<Map<String, Object>> scopes;

    // The AST representation of the code we have to execute.
    private final NodeProgram program;

    // The last value evaluated by an expression over the course of execution.
    private Object lastResult;

    // A flag to store the current jump instruction. Set to null when consumed.
    private JumpOp jump = null;

    // BigDecimal mode makes sure any values that go into or out of externally
    // defined functions are represented in BigDecimal format. This is mainly 
    // for convenience when integrated with Mendix systems.
    private boolean bigDecimalMode = false;

    // Tracks the current line number of execution. This number may not always
    // be accurate.
    private int line = 0;

    // Contstructors
    public Interpreter(String code) { this(code, new HashMap<>()); }
    public Interpreter(String code, Map<String, Object> vars) {
        program = new Parser(code).parse();
        scopes = new LinkedList<>(List.of(new HashMap<>(vars)));
    }

    // MARK: Variables and Scopes
    /**
     * Sets the value of an existing variable. If the variable does not exist,
     * an error is thrown
     */
    public void setVar(String key, Object value) {
        findVar(key)
            .orElseThrow(() -> error("Undefined variable '%s'", key))
            .put(key, value);
    }
    
    /**
     * Defines a new variable with the given value in the current scope. This is
     * allowed to have the same name as another existing variable as long as it
     * lives in an earlier scope, this is what is known as 'shadowing'.
     */
    public void defineVar(String key, Object value) {
        if (scopes.getLast().containsKey(key)) 
            throw error("Redefining an existing variable");
        scopes.getLast().put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getVar(String key) {
        return (T) findVar(key)
            .orElseThrow(() -> error("Variable %s is undefined", key)).get(key);
    }

    // Report if a given variable exists.
    public boolean defined(String key) { return findVar(key).isPresent(); }

    // Find and retrieve a given variable. If not fonud return an empty Optional
    public Optional<Map<String, Object>> findVar(String key) {
        Iterator<Map<String, Object>> itr = scopes.descendingIterator();
        while (itr.hasNext()) {
            final Map<String, Object> scope = itr.next();
            if (scope.containsKey(key)) return Optional.of(scope);
        }

        return Optional.empty();
    }

    // Global scope is special and should never be popped off. It is useful to
    // expose it so different instances can share variables and data.
    public Map<String, Object> getGlobals() { return scopes.getFirst(); }

    // Scopes are popped on and off as execution switches between blocks of
    // statements.
    private void enterScope() { enterScope(new HashMap<>()); }
    private void enterScope(Map<String, Object> scope) { scopes.add(scope); }
    private void exitScope() { scopes.removeLast(); }

    // Miscellanea
    public void setBigDecimalMode(boolean on) { bigDecimalMode = on; }
    public Object getLastResult() { return lastResult; }
    public String toString() { return String.valueOf(program); }
    private String line() { return " (line: " + line + ")"; }
    RuntimeException error(String message, Object... args) {
        return new RuntimeException(String.format(message + line(), args));
    }
    
    // MARK: Run Scope
    public Object run() {
        // Main entry point of execution. Since the program has already been 
        // parsed into a tree, there's little setup for us to do.
        
        // 1. Save a copy of global variables and functions for later.
        final Map<String, Object> inital = new HashMap<>(scopes.getFirst());

        // 2. Add some important standard library functions as variables. Notice
        //    that these can be overwritten by users during normal execution.
        defineVar("exists", (F) a -> defined((String) a[0]));
        defineVar("global", (F) a -> getGlobals().put((String) a[0], null));
        defineVar("type", (F) a -> javaType(a[0]));

        // 3. Run the program.
        runProgram();

        // 4. Restore global variables and functions to how they were initially.
        //    Note that complex objects cannot be restored to normal this way.
        scopes.set(0, inital);

        // 5. Return the last result evaluated 
        return lastResult;
    }
    
    // Running the program itself is quite is easy. Simply run every statement
    // we see in order.
    private void runStmts(List<NodeStmt> stmts) {
        for (int i = 0; i < stmts.size() && jump == null; i += 1) 
            runStmt(stmts.get(i));
    }

    private void runProgram() {
        if (program == null) return;
        runStmts(program.stmts); 
    }

    // Scopes nodes run in the same way that programs do, except wrapped in a 
    // scope of their own. Any variables declared in them disappear afterwards.
    private void runScope(NodeScope scope) {  
        if (scope == null) return;
        enterScope();
        runStmts(scope.stmts);
        exitScope();
    }

    // MARK: Run Statement
    private void runStmt(NodeStmt s) { if (s != null) s.host(stmtVisitor); }
    private final StmtVisitor stmtVisitor = new StmtVisitor(this);
    class StmtVisitor implements NodeStmt.Visitor {

        final Interpreter intr;
        public StmtVisitor(Interpreter interpreter) {
            intr = interpreter;
        }

        @SuppressWarnings("unchecked")
        private void arrayAssign(NodeStmt.Assign a) {
            // To access and update a property of an object (the parent), said 
            // object must first be obtained and evaluated.
            final NodeTerm.ArrayAccess term = (NodeTerm.ArrayAccess) a.term;
            final Object parent = runTerm(term.array);

            // Additionally, the index must also be evaluated.
            final Object index = runExpr(term.index);

            // The index can be a string only if the parent is a map, in which
            // case it works just like a property access.
            if (of(index, String.class) && of(parent, Map.class)) {
                final Map<String, Object> mlhs = (Map<String, Object>) parent;
                final String i = (String) index;
    
                lastResult = calcAssign(intr, a.op, mlhs.get(i), runExpr(a.expr));
                mlhs.put(i, lastResult);
            }

            // Otherwise, if the index is a number and the parent is a List,
            // it can be accessed like an array. If the index expression 
            // evaluates to a float or double, it gets rounded to the closest 
            // integer as specified by their respective intValue() functions.
            else if (of(index, Number.class) && of(parent, List.class)) {
                final List<Object> llhs = (List<Object>) parent;
                final int i = ((Number) index).intValue();
    
                lastResult = calcAssign(intr, a.op, llhs.get(i), runExpr(a.expr));
                llhs.set(i, lastResult);
            }
            
            // Otherwise, if the index is a number and the parent is a string,
            // it can also be accessed like an array to get the n-th character
            // of the string. This is only possible however if the parent term
            // is a NodeVariable.
            else if (of(index, Number.class) && of(parent, String.class) && 
                    of(a.term, NodeTerm.Variable.class)) {
                final String slhs = (String) parent;
                final int i = ((Number) index).intValue();
    
                final char newChar = (char) castValue(intr, "char", calcAssign(intr, 
                    a.op, slhs.charAt(i), runExpr(a.expr)
                ));

                lastResult = slhs.substring(0, i) + String.valueOf(newChar) +
                    (i >= slhs.length() ? "" : slhs.substring(i + 1));

                setVar(((NodeTerm.Variable) a.term).var, lastResult);
            }

            // Otherwise, this is not a valid array access assignment.
            else {
                throw error(
                    "Invalid array access: %s (%s)", term.index, javaType(index)
                );
            }
        }

        @SuppressWarnings("unchecked")
        private void propAssign(NodeStmt.Assign a) {
            // To access and update a property of an object (the parent), said 
            // object must first be obtained and evaluated.
            final NodeTerm.PropAccess term = (NodeTerm.PropAccess) a.term;
            final Object parent = runTerm(term.object); 

            // The parent must be a map.
            if (!of(parent, Map.class))
                throw error(
                    "Invalid map access: %s (%s)", term.object, javaType(parent)
                );
            
            // Grab the value from map and use it to calculate the value of 
            // assignment.
            final Map<String, Object> mlhs = (Map<String, Object>) parent;
            lastResult = calcAssign(intr, a.op, mlhs.get(term.prop), runExpr(a.expr));

            // ... and place this value back into the map.
            mlhs.put(term.prop, lastResult);
        }

        public void visit(NodeStmt.Assign assign) {
            // There are multiple aspects when assignments take place. Following
            // are the rules that used to handle them.
            final Object lhs, value;
            
            // Firstly, we check if the assignee term is a simple variable...
            if (of(assign.term, NodeTerm.Variable.class)) {

                // ... in which case what we have to do is simple; evaluate the
                // RHS (Right-Hand Side) and, according to the assignment 
                // operator, set that result as the value of the variable.
                lhs = getVar(((NodeTerm.Variable) assign.term).var);
                value = calcAssign(intr, assign.op, lhs, runExpr(assign.expr));
                
                // Note that setVar() implicitly checks to see if the variable 
                // is already defined at this point and will throw an error
                // otherwise. To allow assignment to undeclared variables, use 
                // a combination of defined() and define() here instead.
                setVar(((NodeTerm.Variable) assign.term).var, value);
                lastResult = value;
            }

            // Otherwise, it needs to be handled in special ways.
            else if (of(assign.term, NodeTerm.ArrayAccess.class)) {
                arrayAssign(assign);
            }

            else if (of(assign.term, NodeTerm.PropAccess.class)) {
                propAssign(assign);
            }

            // Assignment targets must be variables, array-indexes, or property
            // accesses. An error is thrown if the target is anything else, but
            // this is unlikely to happen as the Parser wouldn't let a term like
            // that through.
            else {
                throw error("LHS of an assignment is invalid: %s", assign.term);
            }
        }

        public void visit(NodeStmt.Declare decl) {            
            final Object value = runExpr(decl.expr);
            defineVar(decl.var, value);
            lastResult = value;
        }

        public void visit(NodeStmt.If stmt) {
            if ((Boolean) castValue(intr, "boolean", runExpr(stmt.expr))) 
                runScope(stmt.succ);
            else if (stmt.fail != null) 
                runScope(stmt.fail);
        }

        public void visit(NodeStmt.TryCatch block) {
            final int scopeCount = scopes.size();
            try {
                runScope(block._try);
            }
            catch (Exception e) {

                // Close all unclosed scopes in the case of an exception catch
                while (scopes.size() > scopeCount) exitScope();
            
                if (block._catch == null ) return;
                enterScope();
                if (block.err != null) defineVar(block.err, e);
                runScope(block._catch);
                exitScope();
            }
            finally {
                if (block._finally != null ) runScope(block._finally);
            }

        }
        
        public void visit(NodeStmt.While loop) {
            while ((Boolean) runExpr(loop.expr)) {
                runScope(loop.scope);
                if (jump == JumpOp.RETURN) break;
                else if (jump == JumpOp.CONTINUE) { jump = null; continue; }
                else if (jump == JumpOp.BREAK) { jump = null; break; }
            }
        }

        public void visit(NodeStmt.ForEach loop) {
            final List<?> list = (List<?>) runTerm(loop.list);
            enterScope();
            defineVar(loop.itr, null);
            for (Object object : list) {
                setVar(loop.itr, object);
                runScope(loop.scope);
                if (jump == JumpOp.RETURN) break;
                else if (jump == JumpOp.CONTINUE) { jump = null; continue; }
                else if (jump == JumpOp.BREAK) { jump = null; break; }
            }
            exitScope();
        }

        public void visit(NodeStmt.For loop) {
            // Plot twist!!
            // For loops are actually while loops in disguise! Muhahaha! 
            enterScope();
            runStmt(loop.init);
            while ((Boolean) runExpr(loop.cond)) {
                runScope(loop.scope);

                if (jump == JumpOp.RETURN) break;
                else if (jump == JumpOp.CONTINUE) { jump = null; }
                else if (jump == JumpOp.BREAK) { jump = null; break; }

                runStmt(loop.inc);
            }
            exitScope();
        }

        public void visit(NodeStmt.Function def) {
            final Capture function = (Capture) exprVisitor.visit(
                new NodeExpr.Lambda(def.params, def.body, line)
            );
            defineVar(def.name, function);
            lastResult = function;
        }

        
        public void visit(NodeStmt.Return stmt) {
            lastResult = runExpr(stmt.expr);
            jump = JumpOp.RETURN;
        }

        public void visit(NodeStmt.Expr exp) { lastResult = runExpr(exp.expr); }
        public void visit(NodeStmt.Scope scope) { runScope(scope.scope); }
        public void visit(NodeStmt.Break stmt) { jump = JumpOp.BREAK; }
        public void visit(NodeStmt.Continue stmt) { jump = JumpOp.CONTINUE; }
    };    

    // MARK: Run Expression
    private Object runExpr(NodeExpr expr) {
        if (expr == null) return null;
        line = expr.line; 
        return expr.host(exprVisitor); 
    }

    private final ExprVisitor exprVisitor = new ExprVisitor(this);
    @SuppressWarnings("unchecked")
    class ExprVisitor implements NodeExpr.Visitor {

        final Interpreter intr;
        public ExprVisitor(Interpreter interpreter) {
            intr = interpreter;
        }

        public Object visit(NodeExpr.Term node) {
            return runTerm(node.val);
        }

        public Object visit(NodeExpr.Binary node) {
            return calcBinary(intr, node.op, runTerm(node.lhs), runTerm(node.rhs));
        }

        public Capture visit(NodeExpr.Lambda def) {
            final F function = (Object... args) -> {
                enterScope();
                for (int i = 0; i < def.params.size(); i += 1) {
                    defineVar(def.params.get(i).param, 
                        i < args.length && args[i] != null ? args[i] :
                        runExpr(def.params.get(i)._default)
                    );
                }

                runScope(def.body);
                exitScope();

                jump = null; // Clear jump flag
                return lastResult;
            };

            return new Capture(scopes, function);
        }
    };

    // MARK: Run Term
    private Object runTerm(NodeTerm term) { return term.host(termVisitor); }
    private final TermVisitor termVisitor = new TermVisitor(this);
    @SuppressWarnings("unchecked")
    class TermVisitor implements NodeTerm.Visitor {

        final Interpreter intr;
        public TermVisitor(Interpreter interpreter) {
            intr = interpreter;
        }

        public <T> T visit(NodeTerm.Literal<?> lit) { return (T) lit.lit; }

        public Object visit(NodeTerm.Variable var) { 
            return getVar(var.var);
        }
        
        public Object visit(NodeTerm.PropAccess paccess) {
            final Object object = runTerm(paccess.object);
            return accessProp(object, paccess.prop);
        }

        public Object visit(NodeTerm.ArrayLiteral arr) {
            return new ArrayList<>(
                arr.items.stream()
                .map(e -> runExpr(e))
                .collect(Collectors.toList())
            );
        }

        public Object visit(NodeTerm.MapLiteral map) {
            return new HashMap<>(
                map.items.stream()
                .collect(Collectors.toMap(e -> e.key, e -> runExpr(e.value)))
            );
        }

        public Object visit(NodeTerm.ArrayAccess access) {
            final Object object = runTerm(access.array);
            final Object i = runExpr(access.index);
            if (of(i, String.class)) {
                return accessProp(object, (String) i);
            }
            else if (longish(i) && of(object, List.class)) {
                return ((List<?>) object).get((int) (long) castValue(intr, "int", i));
            }
            else if (longish(i) && of(object, String.class)) {
                return ((String) object).charAt((int) (long) castValue(intr, "int", i));
            }
            
            throw error("Invalid array access '%s'. Index is of type %s", 
                access, javaType(i));
        }

        public Object visit(NodeTerm.Expr expr) {
            return runExpr(expr.expr);
        }

        public Object visit(NodeTerm.UnaryExpr expr) {
            return calcUnary(intr, expr.op, runTerm(expr.val));
        }

        public Object visit(NodeTerm.Call call) {
            
            final Object f = runTerm(call.f);
            final Object[] argExprs = call.args.stream()
                .map(a -> runExpr(a)).toArray();

            // Experimental
            if (bigDecimalMode) {
                for (int i = 0; i < argExprs.length; i += 1) {
                    if (doublish(argExprs[i])) {
                        argExprs[i] = BigDecimal.valueOf(
                            (Double) castValue(intr, "double", argExprs[i])
                        );
                    }
                }
            }

            if (of(f, Capture.class)) {
                enterScope(((Capture) f).variables);
                final Object value = ((Capture) f).invoke(intr, argExprs);
                exitScope();
                return value;
            }
            else if (of(f, F.class)) {
                final Object value = ((F) f).apply(argExprs);
                
                // Experimental
                if (bigDecimalMode && of(value, BigDecimal.class)) {
                    return castValue(intr, "double", value);
                }

                return value;
            }
            else if (of(f, F0.class)) {
                ((F0) f).apply(argExprs);
                return null;
            }
            
            throw error("Unsupported function type: " + javaType(f));
        }

        public Object visit(NodeTerm.Cast cast) {
            final Object value = runTerm(cast.object);
            return castValue(intr, cast.type.type, value);
        }
    };

    private Object accessProp(Object object, String prop) {
        if (of(object, Map.class)) {
            return ((Map<?, ?>) object).get(prop);
        }
        else if (of(object, String.class)) {
            switch (prop.toLowerCase()) {
                case "size":
                case "length": return ((String) object).length();
                case "split": return ((F) (args) -> { 
                    return ((String) args[0]).split((String) args[1]); 
                });
                // Add more string properties here!
            }

            throw error("Invalid String property: " + prop);
        }
        else if (of(object, List.class)) {
            switch (prop.toLowerCase()) {
                case "size":
                case "length": return ((List<?>) object).size();
                // Add more list properties here!                
            }

            throw error("Invalid List property: " + prop);
        }

        throw error(String.format(
            "Cannot access property '%s' of %s (type: %s)", 
            prop, object, javaType(object)
        ));
    }

}

