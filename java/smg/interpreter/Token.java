package smg.interpreter;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.Set;

/*
 * Token
 * 
 * A Token is a single coherent ordered sequence of characters. Additional info
 * can be stored in a Token about its type and precedence (if applicable). 
 * Tokens that share the same ordered sequence of characters are NOT necessarily 
 * identical. They must also share the same type and precedence. Tokens are 
 * immutable.
 * 
 * Additionally, there are two 'soft' types of Tokens. Tokens that have their
 * values and types known at compile time are definite tokens. You can find a 
 * list of them below. Tokens that are not known at compile time have to be 
 * constructed and instantiated through Token.make().
 * 
 * A list of all definite tokens (except EOT) is collected through reflection 
 * and stored for convenience.
 */
public class Token {

    // Token properties
    
    // Type(s) indicate how the token is used
    final Set<TokenType> types; 

    
    // String value stores the contents of a token.
    final String value; 
    
    // Associativity and Precedence for binary expressions indicate how to build
    // those expressions.
    final boolean rassoc; final int prec; 

    // All definite tokens
    static final Token
    Null = new Token("null", TokenType.Keyword),
    If = new Token("if", TokenType.Keyword),
    Else = new Token("else", TokenType.Keyword),
    While = new Token("while", TokenType.Keyword),
    For = new Token("for", TokenType.Keyword),
    In = new Token("in", TokenType.Keyword),
    Let = new Token("let", TokenType.Keyword),
    Function = new Token("function", TokenType.Keyword),
    Break = new Token("break", TokenType.Keyword),
    Continue = new Token("continue", TokenType.Keyword),
    Try = new Token("try", TokenType.Keyword),
    Catch = new Token("catch", TokenType.Keyword),
    Finally = new Token("finally", TokenType.Keyword),
    Return = new Token("return", TokenType.Keyword),
    As = new Token("as", TokenType.Keyword),
    True = new Token("true", Set.of(TokenType.BooleanLiteral, TokenType.Keyword)),
    False = new Token("false", Set.of(TokenType.BooleanLiteral, TokenType.Keyword)),

    Int = new Token("int", Set.of(TokenType.CastType, TokenType.Keyword)),
    Long = new Token("long", Set.of(TokenType.CastType, TokenType.Keyword)),
    Double = new Token("double", Set.of(TokenType.CastType, TokenType.Keyword)),
    Float = new Token("float", Set.of(TokenType.CastType, TokenType.Keyword)),
    Character = new Token("char", Set.of(TokenType.CastType, TokenType.Keyword)),
    String = new Token("string", Set.of(TokenType.CastType, TokenType.Keyword)),
    Boolean = new Token("boolean", Set.of(TokenType.CastType, TokenType.Keyword)),
    Date = new Token("date", Set.of(TokenType.CastType, TokenType.Keyword)),
    
    Caret = new Token("^", TokenType.BinaryArithmetic, 8, true),
    Asterisk = new Token("*", TokenType.BinaryArithmetic, 7),
    ForwardSlash = new Token("/", TokenType.BinaryArithmetic, 7),
    Percent = new Token("%", TokenType.BinaryArithmetic, 7),
    Plus = new Token("+", TokenType.BinaryArithmetic, 6),
    ShiftLeft = new Token("<<", TokenType.BinaryArithmetic, 5),
    ShiftRight = new Token(">>", TokenType.BinaryArithmetic, 5),
    Greater = new Token(">", TokenType.BinaryArithmetic, 4),
    Less = new Token("<", TokenType.BinaryArithmetic, 4),
    GreaterEqual = new Token(">=", TokenType.BinaryArithmetic, 4),
    LessEqual = new Token("<=", TokenType.BinaryArithmetic, 4),
    Equals = new Token("==", TokenType.BinaryArithmetic, 3),
    NotEquals = new Token("!=", TokenType.BinaryArithmetic, 3),
    Ampersand = new Token("&", TokenType.BinaryArithmetic, 2),
    Pipe = new Token("|", TokenType.BinaryArithmetic, 2),
    Xor = new Token("xor", Set.of(TokenType.BinaryArithmetic, TokenType.Keyword), 2),
    And = new Token("and", Set.of(TokenType.BinaryArithmetic, TokenType.Keyword), 1),
    Or = new Token("or", Set.of(TokenType.BinaryArithmetic, TokenType.Keyword), 1),
    Not = new Token("not", Set.of(TokenType.UnaryArithmetic, TokenType.Keyword)),
    Tilde = new Token("~", TokenType.UnaryArithmetic),

    At = new Token("@", TokenType.Punctuation),
    // Underscore = new Token("_", TokenType.Punctuation), // Yet to be used.
    Hashtag = new Token("#", TokenType.Punctuation),
    Question = new Token("?", TokenType.Punctuation),
    Comma = new Token(",", TokenType.Punctuation),
    Colon = new Token(":", TokenType.Punctuation),
    Period = new Token(".", TokenType.Punctuation),
    BackSlash = new Token("\\", TokenType.Punctuation),
    OpenParen = new Token("(", TokenType.Punctuation),
    CloseParen = new Token(")", TokenType.Punctuation),
    OpenCurly = new Token("{", TokenType.Punctuation),
    OpenSquare = new Token("[", TokenType.Punctuation),
    CloseSquare = new Token("]", TokenType.Punctuation),
    DoubleQuote = new Token("\"", TokenType.Punctuation),
    SingleQuote = new Token("\'", TokenType.Punctuation),
    
    PlusEqual = new Token("+=", TokenType.AssignOperator),
    MultiplyEqual = new Token("*=", TokenType.AssignOperator),
    SubtractEqual = new Token("-=", TokenType.AssignOperator),
    DivideEqual = new Token("/=", TokenType.AssignOperator),
    ModEqual = new Token("%=", TokenType.AssignOperator),
    AndEqual = new Token("&=", TokenType.AssignOperator),
    OrEqual = new Token("|=", TokenType.AssignOperator),
    
    CarriageReturn = new Token("\r", TokenType.WhiteSpace),
    Tab = new Token("\t", TokenType.WhiteSpace),
    BackSpace = new Token("\b", TokenType.WhiteSpace),
    
    EqualSign = new Token("=", Set.of(
        TokenType.Punctuation, TokenType.AssignOperator)),
    Hyphen = new Token("-", Set.of(
        TokenType.BinaryArithmetic, TokenType.UnaryArithmetic), 6),
    Exclaim = new Token("!", Set.of(
        TokenType.Punctuation, TokenType.UnaryArithmetic)),
    SemiColon = new Token(";", Set.of(
        TokenType.Punctuation, TokenType.StatementTerminator)),
    CloseCurly = new Token("}", Set.of(
        TokenType.Punctuation, TokenType.ScopeTerminator)),
    Newline = new Token("\n", Set.of(
        TokenType.WhiteSpace, TokenType.StatementTerminator));

    // End of program indicator
    static final char EOF = '\0';

    // End of tokens indicator
    public static final Token EOT = new Token("EOT", Set.of());

    // List of definite tokens for convenience
    public static final LinkedList<Token> tokenList = new LinkedList<>();

    static {
        try {
            // It is built through reflection
            for (Field f : Token.class.getDeclaredFields()) {
                if (f.getType() == Token.class) {
                    Token t = (Token) f.get(null); 
                    if (t == EOT) continue;
                    tokenList.add(t);
                }
            }

            // And sorted from largest to smallest.
            tokenList.sort((a, b) -> b.value.length() - a.value.length());
        }

        // Do not allow exceptions to be caught here.
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // Constructors
    private Token(String val, TokenType type) { this(val, type, 0); }
    private Token(String val, TokenType type, int p, boolean r) {
        this(val, Set.of(type), p, r);
    }

    private Token(String val, TokenType type, int p) {
        this(val, Set.of(type), p, false);
    }

    private Token(String val, Set<TokenType> types) {
        this(val, types, 0, false);
    }

    private Token(String val, Set<TokenType> types, int p) {
        this(val, types, p, false);
    }

    private Token(String val, Set<TokenType> ts, int p, boolean r) {
        value = val; prec = p; rassoc = r; types = Set.copyOf(ts);
    }

    static Token make(String name, TokenType type) {
        return new Token(name, type);
    }

    // HELPERS
    public boolean hasValue() { return !value.isBlank(); }
    public boolean isAny(TokenType... ts) {
        for (TokenType type : ts) if (types.contains(type)) return true;
        return false;
    }

    public boolean isAll(TokenType... ts) {
        for (TokenType type : ts) if (!types.contains(type)) return false;
        return true;
    }

    private String unescape(String text) {
        return text.replaceAll("\n", "\\\\n")
            .replaceAll("\t", "\\\\t")
            .replaceAll("\r", "\\\\r")
            .replaceAll("\b", "\\\\b")
            .replaceAll("\"", "\\\\\"")
            .replaceAll("\'", "\\\\\'");
    }

    @Override
    public String toString() {
        return (
            types.size() > 0 ? "Special" :
            types.stream().findFirst().get().toString()
        ) + "(\"" + unescape(value) + "\")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Token)) return false;
        
        final Token token = (Token) other;
        return value.equals(token.value) &&
            types.equals(token.types) &&
            prec == token.prec;
    }

    @Override
    public int hashCode() {
        // Not sure how collision free this is. -SMG
        return (this.value.hashCode() + this.types.hashCode()) ^ this.prec;
    }
}