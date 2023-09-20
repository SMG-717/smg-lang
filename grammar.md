Praser syntax:

Scope       -> '{' ([Statement] ('\n' | ';' | EOT))* '}'
Statement   -> [Declaration] | [If] | [While] | [Function] | [Return] | [Scope] | [Assignment] | [Expression]
Assignment  -> [Variable] '=' [Expression] | [ArrayAccess] '=' [Expression]
Declaration -> 'let' [Variable] '=' [Expression] | 'let' [Variable] '=' '[' [Integer] ']'
If          -> 'if' [Expression] [Scope] ('else if' [Expression] [Scope]) ('else' [Scope]) 
While       -> 'while' [Expression] [Scope]
Function    -> 'define' [Variable] '(' ([Variable])* ')' [Scope]
Return      -> 'return' [Expression]

Expression  -> [Term] | [Term] [Operator] [Term]         
Term        -> '(' [Expression] ')' | [Literal] | [Variable] | [Call] | [ArrayAccess]
Call        -> [Variable] '(' ([Expression])* ')'
Literal     -> [Boolean] | [String] | [Integer] | [Decimal] | [Date] | [Array]
ArrayAccess -> [Variable] '[' [Integer] ']'

Boolean     -> 'true' | 'false'
String      -> '\"' ([Anything])* '\"'
Decimal     -> [Integer] '.' [Integer]
Integer     -> ( '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' )*
Date        -> [Integer] '/' [Integer] '/' [Integer]
Array       -> '[' ([Expression])* ']'

Operator    ->  '^'                                 prec = 8
                '*'   | '/'   | '%'                 prec = 7
                '+'   | '-'                         prec = 6
                '<<'  | '>>'                        prec = 5
                '>'   | '<'   | '>='  | '<='        prec = 4
                '=='  | '!='                        prec = 3
                '&'   | '|'   | 'xor'               prec = 2
                'and' | 'or'                        prec = 1


features to add:
- else if
- Arrays
  - Array operations: member access, append, push, pop, 
- Types (?)
<!-- - standalone scopes -->