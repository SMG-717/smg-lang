# Grammar
The following is the grammer of the SMG language. The grammmar is built using
a list of rules called 'productions'. Productions can interact with each other
in intricate ways to create more productions. Strings shown with single quotes
are shorthands for productions that can only generate the given string, for
example, 'let' can only accept the characters 'l', 'e' and 't' in that order.
EOT is a special production that can only accept an 'End of Tokens' token.

Production operations:
[Prod]*            - Repeated 0 or more times
[Prod]+            - Repeated 1 or more times
[Prod]?            - Optional
[Prod] | [Prod]    - A choice between one production and another
([Prod])           - Parenthesis indicate groupings for clarity

# Program
Program     -> [StmtTerm]* ([Statement] ([StmtTerm]+ [Statement])* [StmtTerm]*)?
Scope       -> '{' [Program] '}'
StmtTerm    -> '\n' | ';'
Statement   -> [Scope] | [Assign] | [Expr] | [Control] | [Definition] | [Comment]
Control     -> [If] | [While] | [ForEach] | [ForLoop] | [Break] | [Continue] | [Try]
Definition  -> [Decl] | [Func] | [Return]
Comment     -> '#' (_Anything_)* '\n'

# Statements
Assign      -> ([Qualifier] | [ArrayAccess] | [Accessor]) [AssignOp] [Expr]
Decl        -> 'let' [Qualifier] '=' [Expr]
If          -> 'if' [Expr] [Scope] ('else if' [Expr] [Scope])* ('else' [Scope])?
Try         -> 'try' [Scope] ('catch' [Qualifier] [Scope])? ('finally' [Scope])?
While       -> 'while' [Expr] [Scope]
ForEach     -> 'for' '(' [Qualifier] 'in' [Term] ')' [Scope]
ForLoop     -> 'for' '(' ([Assign] | [Decl])? ';' [Expr]? ';' ([Assign] | [Expr])? ')' [Scope]
Func        -> 'define' [Qualifier] '(' (([Param]) (',' ([Param]))*)? ')' [Scope]
Expr        -> [Term] ([BinaryOp] [Term])?
Return      -> 'return' [Expr]?
Break       -> 'break'
Continue    -> 'continue'

# Terms
Term        -> '(' [Expr] ')' | [ArrayLit] | [MapLit] | [UnaryExpr] | [ArrayAccess] | 
    [Qualifier] | [Accessor] | [Literal] | [FCall] | [Cast]
UnaryExpr   -> [UnaryOp] [Term]
Accessor    -> [Term] '.' [Qualifier]
FCall       -> [Term] '(' ([Expr] (',' [Expr])*)? ')'
Cast        -> [Term] 'as' [Type]

# Maps and Arrays
ArrayAccess -> [Term] '[' [Expr] ']'
Param       -> [Qualifier] ('=' [Expr])?
MapLit      -> '{' ([Qualifier] ':' [Expr] (',' [Qualifier] ':' [Expr])*)? '}'
ArrayLit    -> '[' ([Expr] (',' [Expr])*)? ']'

# Qualifier
Qualifier   -> ([Letter] | [Underscore]) ([Letter] | [Underscore] | [Digit])* 
Letter      -> [A-Za-z]
Underscore  -> '_'

# Literals
Literal     -> [Empty] | [Boolean] | [String] | [Decimal] | [Integer]
Empty       -> 'empty'
Boolean     -> 'true' | 'false'
String      -> ('\"' (_Anything_)* '\"') | ('\'' (_Anything_)* '\'')
Decimal     -> [Integer] '.' [Integer]
Integer     -> [Digit]+
Digit       -> '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9'
Type        -> 'int' | 'long' | 'double' | 'float' | 'char' | 'string' | 'boolean'

# Operators
AssignOp    -> '=' | '^=' | '*=' | '/=' | '%=' | '+=' | '-=' | '&=' | '|='
UnaryOp     -> '-' | '~' | '!' | 'not'
BinaryOp    -> '^' | '*' | '/' | '%' | '+' | '-' | '<<' | '>>' | 
    '>' | '<' | '>=' | '<=' | '==' | '!=' | '&' | '|' | 'xor' | 'and' | 'or'

