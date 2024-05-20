package smg.interpreter;

/*
 * Token Types
 * 
 * Used in the parser for a lot of manipulation, it helps identify quickly if a 
 * Token belongs in an expression or not. Note that there may be unused types in
 * the current implementation of the Tokeniser.
 */
public enum TokenType {
    Qualifier,
    Keyword,
    CastType,
    Punctuation,
    WhiteSpace,
    Comment,
    NumberLiteral,
    StringLiteral,
    BooleanLiteral,
    FunctionDecl,
    DateLiteral,
    BinaryArithmetic,
    UnaryArithmetic,
    AssignOperator,
    StatementTerminator,
    ScopeTerminator;
}