BExp        -> [Comp] | ([BExp]) | not ([BExp]) | [BExp] b.op [BExp]        (b.op = {'and', 'or'})
Comp        -> [Term] | [Term] c.op [Term]                                  (c.op = {'>', '>=', '<', '<=', '=', '!='})
Term        -> [Atom] | 
               [Term] a.op [Term]                                           (a.op = {'+', '-', }, prec = 1)
               [Term] a.op [Term]                                           (a.op = {'*', '/', '%'}, prec = 2)
               [Term] a.op [Term]                                           (a.op = {'^'}, prec = 3)
Atom        -> ([Term]) | [Literal] | [Variable]
Literal     -> [BooleanLiteral] | [StringLiteral] | [NumberLiteral] | [DateLiteral]
Viariable   -> [Qualifier] | [Qualifier].[Qualifier]