package nta.engine.parser;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import nta.catalog.*;
import nta.catalog.exception.NoSuchTableException;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.catalog.proto.CatalogProtos.FunctionType;
import nta.catalog.proto.CatalogProtos.IndexMethod;
import nta.catalog.proto.CatalogProtos.StoreType;
import nta.datum.DatumFactory;
import nta.engine.Context;
import nta.engine.QueryContext;
import nta.engine.exception.InternalException;
import nta.engine.exec.eval.*;
import nta.engine.exec.eval.InvalidEvalException;
import nta.engine.function.AggFunction;
import nta.engine.function.GeneralFunction;
import nta.engine.parser.QueryBlock.*;
import nta.engine.planner.JoinType;
import nta.engine.query.exception.*;
import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.Tree;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * This class transforms a query statement into a QueryBlock. 
 * 
 * @author Hyunsik Choi
 * 
 * @see QueryBlock
 */
public final class QueryAnalyzer {
  private static final Log LOG = LogFactory.getLog(QueryAnalyzer.class);
  private final CatalogService catalog;
  
  public QueryAnalyzer(CatalogService catalog) {
    this.catalog = catalog;
  }

  public ParseTree parse(final Context ctx, final String query) {
    CommonTree ast = parseTree(query);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Analyzer: " + ast.toStringTree());
    }
    return parseQueryTree(ctx, ast);
  }
  
  private ParseTree parseQueryTree(final Context ctx, CommonTree ast) {
    ParseTree parseTree = null;
    
    switch (getCmdType(ast)) {
    case SELECT:
      parseTree = parseSelectStatement(ctx, ast);
      break;
      
    case UNION:
    case EXCEPT:
    case INTERSECT:
      parseTree = parseSetStatement(ctx, ast);
      break;
      
    case CREATE_INDEX:
      parseTree = parseIndexStatement(ctx, ast);
      break;
    
    case CREATE_TABLE:
      parseTree = parseCreateStatement(ctx, ast);
    default:
      break;
    }

    ctx.makeHints(parseTree);
    return parseTree;
  }
  
  /**
   * t=table ASSIGN select_stmt -> ^(CREATE_TABLE $t select_stmt)
   * | CREATE TABLE t=table AS select_stmt -> ^(CREATE_TABLE $t select_stmt)
   * 
   * @param ctx
   * @param ast
   * @return
   */
  private CreateTableStmt parseCreateStatement(final Context ctx,
      final CommonTree ast) {
    CreateTableStmt stmt;
    
    int idx = 0;
    CommonTree node;
    String tableName = ast.getChild(idx).getText();
    idx++;
    node = (CommonTree) ast.getChild(idx);
    
    if (node.getType() == NQLParser.TABLE_DEF) {
      Schema tableDef = parseCreateTableDef(ctx, node);
      idx++;      
      StoreType storeType = ParseUtil.getStoreType(ast.getChild(idx).getText());
      idx++;
      Path path = new Path(ast.getChild(idx).getText());
      stmt = new CreateTableStmt(tableName, tableDef, storeType, path);      
      if ((ast.getChildCount() - idx) > 1) {
        idx++;
        if (ast.getChild(idx).getType() == NQLParser.PARAMS) {
          Options options = parseParams(ctx, (CommonTree) ast.getChild(idx));
          stmt.setOptions(options);
        }
      }      
    } else if (node.getType() == NQLParser.SELECT) {
      QueryBlock selectStmt = parseSelectStatement(ctx, node);    
      stmt = new CreateTableStmt(tableName, selectStmt);      
    } else {    
      throw new NotSupportQueryException("ERROR: not yet supported query");
    }
    
    return stmt;
  }
  
  private Schema parseCreateTableDef(final Context ctx, final CommonTree ast) {
    Schema tableDef = new Schema();
    DataType type;
    for (int i = 0; i < ast.getChildCount(); i++) {
      switch(ast.getChild(i).getChild(1).getType()) {      
      case NQLParser.BOOL: type = DataType.BOOLEAN; break;
      case NQLParser.BYTE: type = DataType.BYTE; break;
      case NQLParser.INT: type = DataType.INT; break;                                 
      case NQLParser.LONG: type = DataType.LONG; break;
      case NQLParser.FLOAT: type = DataType.FLOAT; break;
      case NQLParser.DOUBLE: type = DataType.DOUBLE; break;
      case NQLParser.TEXT: type = DataType.STRING; break;
      case NQLParser.BYTES: type = DataType.BYTES; break;
      case NQLParser.IPv4: type = DataType.IPv4; break;
      default: throw new InvalidQueryException(ast.toStringTree());
      }                                       
      
      tableDef.addColumn(ast.getChild(i).getChild(0).getText(), type);                                   
    }
    
    return tableDef;
  }  
  
  private SetStmt parseSetStatement(final Context ctx,
      final CommonTree ast) {
    StatementType type;
    boolean distinct = true;
    ParseTree left;
    ParseTree right;
    
    switch (ast.getType()) {
    case NQLParser.UNION:
      type = StatementType.UNION;
      break;
    case NQLParser.EXCEPT:
      type = StatementType.EXCEPT;
      break;
    case NQLParser.INTERSECT:
      type = StatementType.INTERSECT;
      break;
    default:
       throw new InvalidQueryException("Illegal AST:\n" + ast.toStringTree());
    }
    
    int idx = 0;
    QueryContext leftCtx = new QueryContext(catalog);
    left = parseQueryTree(leftCtx, (CommonTree) ast.getChild(idx));
    idx++;    
    int nodeType = ast.getChild(idx).getType();
    if (nodeType == NQLParser.ALL) {
      distinct = true;
      idx++;
    } else if (nodeType == NQLParser.DISTINCT) {
      distinct = false;
      idx++;
    }
    QueryContext rightCtx = new QueryContext(catalog);
    right = parseQueryTree(rightCtx, (CommonTree) ast.getChild(idx));
    ctx.mergeContext(leftCtx);
    ctx.mergeContext(rightCtx);
    return new SetStmt(type, left, right, distinct);
  }

  private QueryBlock parseSelectStatement(final Context ctx,
      final CommonTree ast) {
    
    QueryBlock block = new QueryBlock();

    CommonTree node;
    for (int cur = 0; cur < ast.getChildCount(); cur++) {
      node = (CommonTree) ast.getChild(cur);

      switch (node.getType()) {
      case NQLParser.FROM:
        parseFromClause(ctx, block, node);
        break;
              
      case NQLParser.SET_QUALIFIER:
        parseSetQualifier(ctx, block, node);
        break;
        
      case NQLParser.SEL_LIST:
        parseSelectList(ctx, block, node);
        break;
        
      case NQLParser.WHERE:
        parseWhereClause(ctx, block, node);
        break;

      case NQLParser.GROUP_BY:
        parseGroupByClause(ctx, block, node);
        break;
        
      case NQLParser.HAVING:
        parseHavingClause(ctx, block, node);
        break;
        
      case NQLParser.ORDER_BY:
        SortSpec [] sortKeys = parseSortSpecifiers(ctx, 
            (CommonTree) node.getChild(0));
        block.setSortKeys(sortKeys);
        break;        
        
      default:
        
      }
    }

    return block;
  }

  private void parseSetQualifier(final Context ctx, final QueryBlock block, final CommonTree ast) {
    int idx = 0;

    if (ast.getChild(idx).getType() == NQLParser.DISTINCT) {
      block.setDistinct();
    }
  }
  
  /**
   * EBNF: CREATE (UNIQUE?) INDEX n=ID ON t=ID LEFT_PAREN s=sort_specifier_list 
   * RIGHT_PAREN p=param_clause? <br />
   * AST:  ^(CREATE_INDEX $n $t $s $p)
   * 
   * @param ctx
   * @param ast
   */
  private CreateIndexStmt parseIndexStatement(final Context ctx,
      final CommonTree ast) {
    
    int idx = 0;
    boolean unique = false;
    // the below things are optional
    if (ast.getChild(idx).getType() == NQLParser.UNIQUE) {
      unique = true;
      idx++;
    }
    
    IndexMethod method = null;
    if (ast.getChild(idx).getType() == NQLParser.USING) {
      method = getIndexMethod(ast.getChild(idx).getText());
      idx++;
    }
    
    // It's optional, so it can be null if there is no params clause.
    Options params = null;
    if (ast.getChild(idx).getType() == NQLParser.PARAMS) {
      params = parseParams(ctx, (CommonTree) ast.getChild(idx));
      idx++;
    }
    
    // They are required, so they are always filled.
    String idxName = ast.getChild(idx++).getText();
    String tbName = ast.getChild(idx++).getText();
    ctx.renameTable(tbName, tbName);
    
    SortSpec [] sortSpecs = parseSortSpecifiers(ctx, 
        (CommonTree) ast.getChild(idx++));

    CreateIndexStmt stmt = new CreateIndexStmt(idxName, unique, tbName, 
        sortSpecs);
    if (method != null) {
      stmt.setMethod(method);
    }
    
    if (params != null) {
      stmt.setParams(params);
    }
      
    return stmt;
  }
  
  /**
   * EBNF: table_list -> tableRef (COMMA tableRef)
   * @param block
   * @param ast
   */
  private void parseFromClause(final Context ctx,
                               final QueryBlock block, final CommonTree ast) {
    if (ast.getChild(0).getType() == NQLParser.JOIN) { // explicit join
      JoinClause joinClause = parseExplicitJoinClause(ctx, block, 
          (CommonTree) ast.getChild(0));
      block.setJoinClause(joinClause);

    } else {
      // implicit join or the from clause on single relation
      FromTable table;
      CommonTree node;
      for (int i = 0; i < ast.getChildCount(); i++) {
        node = (CommonTree) ast.getChild(i);

        switch (node.getType()) {

          case NQLParser.TABLE:
            // table (AS ID)?
            // 0 - a table name, 1 - table alias
            table = parseTable(ctx, block, node);
            ctx.renameTable(table.getTableName(),
                table.hasAlias() ? table.getAlias() : table.getTableName());
            block.addFromTable(table);
            break;

          default:
        } // switch
      } // for each derievedTable
    }
  }
  
  private JoinClause parseExplicitJoinClause(final Context ctx, final QueryBlock block, 
      final CommonTree ast) {
    
    int idx = 0;
    int parsedJoinType = ast.getChild(idx).getType();
    JoinType joinType = null;
    
    switch (parsedJoinType) {
    case NQLParser.NATURAL_JOIN:
      joinType = JoinType.NATURAL;
      break;    
    case NQLParser.INNER_JOIN:
      joinType = JoinType.INNER;      
      break;
    case NQLParser.OUTER_JOIN:
      CommonTree outerAST = (CommonTree) ast.getChild(0);
      if (outerAST.getChild(0).getType() == NQLParser.LEFT) {
        joinType = JoinType.LEFT_OUTER;
      } else if (outerAST.getChild(0).getType() == NQLParser.RIGHT) {
        joinType = JoinType.RIGHT_OUTER;
      }
      break;
    case NQLParser.CROSS_JOIN:
      joinType = JoinType.CROSS_JOIN;
      break;
    }
    
    idx++; // 1
    FromTable left = parseTable(ctx, block, (CommonTree) ast.getChild(idx));
    ctx.renameTable(left.getTableName(),
        left.hasAlias() ? left.getAlias() : left.getTableName());
    JoinClause joinClause = new JoinClause(joinType, left);
    
    idx++; // 2
    if (ast.getChild(idx).getType() == NQLParser.JOIN) {
      joinClause.setRight(parseExplicitJoinClause(ctx, block, 
          (CommonTree) ast.getChild(idx)));
    } else {
      FromTable right = parseTable(ctx, block, 
          (CommonTree) ast.getChild(idx));
      ctx.renameTable(right.getTableName(),
          right.hasAlias() ? right.getAlias() : right.getTableName());
      block.addFromTable(right);
      joinClause.setRight(right);
    }
    
    idx++; // 3
    if (ast.getChild(idx) != null) {
      if (joinType == JoinType.NATURAL) {
        throw new InvalidQueryException("Cross or natural join cannot have join conditions");
      }
      
      CommonTree joinQual = (CommonTree) ast.getChild(idx);
      if (joinQual.getType() == NQLParser.ON) {
        EvalNode joinCond = parseJoinCondition(ctx, block, joinQual);
        joinClause.setJoinQual(joinCond);
      } else if (joinQual.getType() == NQLParser.USING) {
        Column [] joinColumns = parseJoinColumns(ctx, block, joinQual);
        joinClause.setJoinColumns(joinColumns);
      }
    }
    
    return joinClause;
  }
  
  private Column [] parseJoinColumns(Context ctx, QueryBlock block, 
      CommonTree ast) {
    Column [] joinColumns = new Column[ast.getChildCount()]; 
    for (int i = 0; i < ast.getChildCount(); i++) {
      joinColumns[i] = checkAndGetColumnByAST(ctx, (CommonTree) ast.getChild(i));
    }
    return joinColumns;
  }
  
  private EvalNode parseJoinCondition(Context ctx, QueryBlock block, 
      CommonTree ast) {
    return createEvalTree(ctx, ast.getChild(0), block);
  }
  
  private static FromTable parseTable(final Context ctx, final QueryBlock block,
      final CommonTree tableAST) {
    String tableName = tableAST.getChild(0).getText();
    TableDesc desc = checkAndGetTableByName(ctx, tableName);
    FromTable table;
    if (tableAST.getChildCount() > 1) {
      table = new FromTable(desc, 
          tableAST.getChild(1).getText());
    } else {
      table = new FromTable(desc);
    }
    
    return table;
  }
  
  /**
   * This method parses the select list of a query statement.
   * <pre>
   * EBNF: 
   * 
   * selectList
   * : MULTIPLY -> ^(SEL_LIST ALL)
   * | derivedColumn (COMMA derivedColumn)* -> ^(SEL_LIST derivedColumn+)
   * ;
   * 
   * derivedColumn
   * : bool_expr asClause? -> ^(COLUMN bool_expr asClause?)
   * ;
   * 
   * @param block
   * @param ast
   */
  private void parseSelectList(final Context ctx, 
      final QueryBlock block, final CommonTree ast) {    
  
    if (ast.getChild(0).getType() == NQLParser.ALL) {
      block.setProjectAll();
    } else {
      CommonTree node;
      int numTargets = ast.getChildCount();
      Target [] targets = new Target[numTargets];
      EvalNode evalTree;
      String alias;
      
      // the final one for each target is the alias
      // EBNF: bool_expr AS? fieldName
      for (int i = 0; i < ast.getChildCount(); i++) {        
        node = (CommonTree) ast.getChild(i);
        evalTree = createEvalTree(ctx, node, block);
        targets[i] = new Target(evalTree, i);
        if (node.getChildCount() > 1) {          
          alias = node.getChild(node.getChildCount() - 1).getChild(0).getText();
          targets[i].setAlias(alias);
        }
      }
      
      block.setTargetList(targets);
    }    
  }
  
  private void parseWhereClause(final Context ctx, 
      final QueryBlock block, final CommonTree ast) {
    EvalNode whereCond = createEvalTree(ctx, ast.getChild(0), block);        
    block.setWhereCondition(whereCond);    
  }
  
  /**
   * See 'groupby_clause' rule in NQL.g
   * 
   * @param ctx
   * @param block
   * @param ast
   */
  private void parseGroupByClause(final Context ctx, 
      final QueryBlock block, final CommonTree ast) {
    GroupByClause clause = new GroupByClause();
    
    int idx = 0;
    
    if (ast.getChild(idx).getType() == NQLParser.EMPTY_GROUPING_SET) {
      clause.setEmptyGroupSet();
    } else {
      // the remain ones are grouping fields.
      Tree group;
      List<Column> columnRefs = new ArrayList<Column>();
      Column [] columns;
      Column column;
      for (; idx < ast.getChildCount(); idx++) {
        group = ast.getChild(idx);
        switch (group.getType()) {
        case NQLParser.CUBE:
          columns = parseColumnReferences(ctx, (CommonTree) group);
          GroupElement cube = new GroupElement(GroupType.CUBE, columns);
          clause.addGroupSet(cube);
          break;
          
        case NQLParser.ROLLUP:
          columns = parseColumnReferences(ctx, (CommonTree) group);
          GroupElement rollup = new GroupElement(GroupType.ROLLUP, columns);
          clause.addGroupSet(rollup);
          break;
          
        case NQLParser.FIELD_NAME:
          column = checkAndGetColumnByAST(ctx, (CommonTree) group);
          columnRefs.add(column);
          break;
        }
      }
      
      if (columnRefs.size() > 0) {        
        Column [] groupingFields = columnRefs.toArray(new Column[columnRefs.size()]);
        GroupElement g = new GroupElement(GroupType.GROUPBY, groupingFields);
        clause.addGroupSet(g);
      }
    }
    
    block.setGroupByClause(clause);
  }
  
  private void parseHavingClause(final Context ctx,
      final QueryBlock block, final CommonTree ast) {
    EvalNode evalTree = 
        createEvalTree(ctx, ast.getChild(0), block);
    block.setHavingCond(evalTree);
  }
  
  /**
   * Should be given Params Node
   * 
   * EBNF: WITH LEFT_PAREN param (COMMA param)* RIGHT_PAREN 
   * AST: ^(PARAMS param+)
   * 
   * @param ctx
   * @param ast
   * @return
   */
  private static Options parseParams(final Context ctx,
      final CommonTree ast) {
    Options params = new Options();
    
    Tree child;
    for (int i = 0; i < ast.getChildCount(); i++) {
      child = ast.getChild(i);
      params.put(child.getChild(0).getText(), child.getChild(1).getText());
    }
    return params;
  }
  

  /**
   * Should be given SortSpecifiers Node
   * 
   * EBNF: sort_specifier (COMMA sort_specifier)* -> sort_specifier+
   * 
   * @param ctx
   * @param ast
   */
  private static SortSpec [] parseSortSpecifiers(final Context ctx, 
      final CommonTree ast) {
    int numSortKeys = ast.getChildCount();
    SortSpec[] sortKeys = new SortSpec[numSortKeys];
    CommonTree node;
    Column column;
    
    // Each child has the following EBNF and AST:
    // EBNF: fn=fieldName a=order_specification? o=null_ordering? 
    // AST: ^(SORT_KEY $fn $a? $o?)
    for (int i = 0; i < numSortKeys; i++) {
      node = (CommonTree) ast.getChild(i);
      column = checkAndGetColumnByAST(ctx, (CommonTree) node.getChild(0));
      sortKeys[i] = new SortSpec(column);
            
      if (node.getChildCount() > 1) {
        Tree child;
        for (int j = 1; j < node.getChildCount(); j++) {
          child = node.getChild(j);
          
          // AST: ^(ORDER ASC) | ^(ORDER DESC)
          if (child.getType() == NQLParser.ORDER) {
            if (child.getChild(0).getType() == NQLParser.DESC) {
              sortKeys[i].setDescOrder();
            }            
          } else if (child.getType() == NQLParser.NULL_ORDER) {
            // AST: ^(NULL_ORDER FIRST) | ^(NULL_ORDER LAST)
            if (child.getChild(0).getType() == NQLParser.FIRST) {
              sortKeys[i].setNullFirst();
            }
          }          
        }
      }
    }
    
    return sortKeys;
  }  
  
  private static Column checkAndGetColumnByAST(final Context ctx,
      final CommonTree fieldNode) {
    Preconditions.checkArgument(NQLParser.FIELD_NAME == fieldNode.getType());
    
    String columnName = fieldNode.getChild(0).getText();
    String tableName = null;
    if (fieldNode.getChildCount() > 1) {
      tableName = fieldNode.getChild(1).getText();
    }
    
    Column column;
    if(tableName != null) {
      TableDesc desc;
      desc = checkAndGetTableByMappedName(ctx, tableName);  
      column = checkAndGetFieldByName(desc, columnName);
    } else {
      column = expectTableByField(ctx, columnName);
    }
    
    return column;
  }
  
  private static TableDesc checkAndGetTableByMappedName(final Context ctx,
      final String tableName) {
      String realName = ctx.getActualTableName(tableName);
      return checkAndGetTableByName(ctx, realName);
  }
  
  private static TableDesc checkAndGetTableByName(final Context ctx,
      final String tableName) {
    TableDesc desc;

    try {
      desc =
          ctx.getTable(tableName);
    } catch (NoSuchTableException nst) {
      throw new InvalidQueryException("ERROR: table \"" + tableName
          + "\" does not exist");
    }

    return desc;
  }
  
  private static Column checkAndGetFieldByName(final TableDesc desc,
      final String columnName) {
    Column column;
    
    column = desc.getMeta().getSchema().getColumn(desc.getId()+"."+columnName);
    if(column == null) {
      throw new InvalidQueryException("ERROR: column \"" + columnName
          + "\" does not exist");
    }
    
    return column;
  }
  
  /**
   * determine a column by finding tables which are given by 'from clause'.
   *  
   * @param ctx
   * @param columnName field name to be find
   * @return a found column
   */
  private static Column expectTableByField(Context ctx, String columnName) {
    TableDesc desc;
    Schema schema;
    Column column = null;    
    int count = 0;
    for(String table : ctx.getInputTables()) {
      desc =
          ctx.getTable(table);
      schema = desc.getMeta().getSchema();
      
      if(schema.contains(table+"."+columnName)) {
        column = schema.getColumn(table+"."+columnName);
        count++;
      }      
      
      // if there are more than one column, we cannot expect
      // that this column belongs to which table.
      if(count > 1) 
        throw new AmbiguousFieldException(columnName);
    }
    
    if(column == null) { // if there are no matched column
      throw new InvalidQueryException("ERROR: column \"" + columnName
          + "\" does not exist");
    }
    
    return column;
  }

  private static CommonTree parseTree(final String query) {
    ANTLRStringStream input = new ANTLRStringStream(query);
    NQLLexer lexer = new NQLLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    NQLParser parser = new NQLParser(tokens);

    CommonTree ast;
    try {
      ast = ((CommonTree) parser.statement().getTree());
    } catch (RecognitionException e) {
      throw new NQLSyntaxException(query);
    }

    if (ast.getType() == 0) {
      throw new NQLSyntaxException(query);
    }

    return ast;
  }

  private static StatementType getCmdType(final CommonTree ast) {
    switch (ast.getType()) {
    case NQLParser.STORE:
      return StatementType.STORE;
    case NQLParser.SELECT:
      return StatementType.SELECT;
    case NQLParser.UNION:
      return StatementType.UNION;
    case NQLParser.EXCEPT:
      return StatementType.EXCEPT;
    case NQLParser.INTERSECT:
      return StatementType.INTERSECT;
    case NQLParser.INSERT:
      return StatementType.INSERT;
    case NQLParser.CREATE_INDEX:
      return StatementType.CREATE_INDEX;
    case NQLParser.CREATE_TABLE:
      return StatementType.CREATE_TABLE;
    case NQLParser.DROP_TABLE:
      return StatementType.DROP_TABLE;
    case NQLParser.SHOW_TABLE:
      return StatementType.SHOW_TABLES;
    case NQLParser.DESC_TABLE:
      return StatementType.DESC_TABLE;
    case NQLParser.SHOW_FUNCTION:
      return StatementType.SHOW_FUNCTION;
    default:
      return null;
    }
  }
  
  private static IndexMethod getIndexMethod(String method) {
    Preconditions.checkNotNull(method);
    if (method.equals("bst")) {
      return IndexMethod.TWO_LEVEL_BIN_TREE;
    } else if (method.equals("btree")) {
      return IndexMethod.BTREE;
    } else if (method.equals("hash")) {
      return IndexMethod.HASH;
    } else if (method.equals("bitmap")) {
      return IndexMethod.BITMAP;
    } else {
      throw new NQLSyntaxException("ERROR: unknown index: " + method);
    }
  }
  
  public EvalNode createEvalTree(final Context ctx, 
      final Tree ast, QueryBlock query) {
    switch(ast.getType()) {

    // constants
    case NQLParser.DIGIT:
      return new ConstEval(DatumFactory.createInt(
          Integer.valueOf(ast.getText())));

    case NQLParser.REAL:
      return new ConstEval(DatumFactory.createDouble(
          Double.valueOf(ast.getText())));

    case NQLParser.STRING:
      return new ConstEval(DatumFactory.createString(ast.getText()));

    // unary expression
    case NQLParser.NOT:
      return new NotEval(createEvalTree(ctx, ast.getChild(0), query));

    // binary expressions
    case NQLParser.LIKE:
        return parseLike(ctx, ast, query);
    case NQLParser.AND:
    case NQLParser.OR:
    case NQLParser.EQUAL:
    case NQLParser.NOT_EQUAL:
    case NQLParser.LTH:
    case NQLParser.LEQ:
    case NQLParser.GTH:
    case NQLParser.GEQ:
    case NQLParser.PLUS:
    case NQLParser.MINUS:
    case NQLParser.MULTIPLY:
    case NQLParser.DIVIDE:
    case NQLParser.MODULAR:
      return parseBinaryExpr(ctx, ast, query);

    // others
    case NQLParser.COLUMN:
      return createEvalTree(ctx, ast.getChild(0), query);
      
    case NQLParser.FIELD_NAME:              
      Column column = checkAndGetColumnByAST(ctx, (CommonTree) ast);
      return new FieldEval(column); 
      
    case NQLParser.FUNCTION:
      String signature = ast.getText();
            
      EvalNode [] givenArgs = new EvalNode[ast.getChildCount()];
      DataType [] paramTypes = new DataType[ast.getChildCount()];

      for (int i = 0; i < ast.getChildCount(); i++) {
        givenArgs[i] = createEvalTree(ctx, ast.getChild(i), query);
        paramTypes[i] = givenArgs[i].getValueType();
      }
      if (!catalog.containFunction(signature, paramTypes)) {
        throw new UndefinedFunctionException(TCatUtil.
            getCanonicalName(signature, paramTypes));
      }
      FunctionDesc funcDesc = catalog.getFunction(signature, paramTypes);
      try {
        if (funcDesc.getFuncType() == FunctionType.GENERAL)
          return new FuncCallEval(funcDesc, (GeneralFunction) funcDesc.newInstance(), givenArgs);
        else {
          query.setAggregation();
          return new AggFuncCallEval(funcDesc, (AggFunction) funcDesc.newInstance(), givenArgs);
        }
      } catch (InternalException e) {
        e.printStackTrace();
      }
      
      break;
    case NQLParser.COUNT_VAL:
      // Getting the first argument
      EvalNode colRef = createEvalTree(ctx, ast.getChild(0), query);
      
      FunctionDesc countVals = catalog.getFunction("count", 
          new DataType [] {DataType.ANY});
      query.setAggregation();
      try {
        return new AggFuncCallEval(countVals, (AggFunction) countVals.newInstance(),
            new EvalNode [] {colRef});
      } catch (InternalException e1) {
        e1.printStackTrace();
      }
      break;
      
    case NQLParser.COUNT_ROWS:
      FunctionDesc countRows = catalog.getFunction("count", new DataType [] {});
      query.setAggregation();
      try {
        return new AggFuncCallEval(countRows, (AggFunction) countRows.newInstance(),
            new EvalNode [] {});
      } catch (InternalException e) {
        e.printStackTrace();
      }
      break;

    case NQLParser.CASE:
      return parseCaseWhen(ctx, ast, query);
      
    default:
    }
    return null;
  }

  /**
   * The EBNF of case statement
   * <pre>
   * searched_case
   * : CASE s=searched_when_clauses e=else_clause END -> ^(CASE $s $e)
   * ;
   *
   * searched_when_clauses
   * : searched_when_clause searched_when_clause* -> searched_when_clause+
   * ;
   *
   * searched_when_clause
   * : WHEN c=search_condition THEN r=result -> ^(WHEN $c $r)
   * ;
   *
   * else_clause
   * : ELSE r=result -> ^(ELSE $r)
   * ;
   * </pre>
   * @param ctx
   * @param tree
   * @param block
   * @return
   */
  public CaseWhenEval parseCaseWhen(Context ctx, Tree tree, QueryBlock block) {
    int idx = 0;

    CaseWhenEval caseEval = new CaseWhenEval();
    EvalNode cond;
    EvalNode thenResult;
    Tree when;
    for (; idx < tree.getChildCount() && tree.getChild(idx).getType() == NQLParser.WHEN; idx++) {
      when = tree.getChild(idx);
      cond = createEvalTree(ctx, when.getChild(0), block);
      thenResult = createEvalTree(ctx, when.getChild(1), block);
      caseEval.addWhen(cond, thenResult);
    }

    if (tree.getChild(idx) != null && tree.getChild(idx).getType() == NQLParser.ELSE) {
      EvalNode elseResult = createEvalTree(ctx, tree.getChild(idx).getChild(0), block);
      caseEval.setElseResult(elseResult);
    }

    return caseEval;
  }

  public EvalNode parseDigitByTypeInfer(Context ctx, Tree tree, QueryBlock block, DataType type) {
    switch (type) {
      case SHORT:
        return new ConstEval(DatumFactory.createShort(tree.getText()));
      case INT:
        return new ConstEval(DatumFactory.createInt(tree.getText()));
      case LONG:
        return new ConstEval(DatumFactory.createLong(tree.getText()));
      default: return createEvalTree(ctx, tree, block);
    }
  }

  private EvalNode parseRealByTypeInfer(Context ctx, Tree tree, QueryBlock block, DataType type) {
    switch (type) {
      case FLOAT:
        return new ConstEval(DatumFactory.createFloat(tree.getText()));
      case DOUBLE:
        return new ConstEval(DatumFactory.createDouble(tree.getText()));
      default: return createEvalTree(ctx, tree, block);
    }
  }

  private EvalNode parseStringByTypeInfer(Context ctx, Tree tree, QueryBlock block, DataType type) {
    switch (type) {
      case CHAR:
        return new ConstEval(DatumFactory.createChar(tree.getText().charAt(0)));
      case STRING:
        return new ConstEval(DatumFactory.createString(tree.getText()));
      default: return createEvalTree(ctx, tree, block);
    }
  }

  @VisibleForTesting
  EvalNode parseBinaryExpr(Context ctx, Tree tree, QueryBlock block) {
    int constId = -1;
    int fieldId = -1;

    for (int i = 0; i < 2; i++) {
      if (ParseUtil.isConstant(tree.getChild(i))) {
        constId = i;
      } else if (tree.getChild(i).getType() == NQLParser.FIELD_NAME) {
        fieldId = i;
      }
    }

    if (constId != -1 && fieldId != -1) {
      EvalNode [] exprs = new EvalNode[2];
      exprs[fieldId] = (FieldEval) createEvalTree(ctx, tree.getChild(fieldId), block);

      Tree constAst = tree.getChild(constId);
      switch (tree.getChild(constId).getType()) {
        case NQLParser.DIGIT:
          exprs[constId] = parseDigitByTypeInfer(ctx, constAst, block, exprs[fieldId].getValueType());
          break;
        case NQLParser.REAL:
          exprs[constId] = parseRealByTypeInfer(ctx, constAst, block, exprs[fieldId].getValueType());
          break;
        case NQLParser.STRING:
          exprs[constId] = parseStringByTypeInfer(ctx, constAst, block, exprs[fieldId].getValueType());
          break;
        default: throw new  InvalidEvalException();
      }

      if (constId == 0) {
        return new BinaryEval(ParseUtil.getTypeByParseCode(tree.getType()), exprs[constId], exprs[fieldId]);
      } else {
        return new BinaryEval(ParseUtil.getTypeByParseCode(tree.getType()), exprs[fieldId], exprs[constId]);
      }
    } else {
      return new BinaryEval(ParseUtil.getTypeByParseCode(tree.getType()),
          createEvalTree(ctx, tree.getChild(0), block), createEvalTree(ctx, tree.getChild(1), block));
    }
  }
  
  /**
   * <pre>
   * like_predicate : fieldName NOT? LIKE string_value_expr 
   * -> ^(LIKE NOT? fieldName string_value_expr)
   * </pre>
   * @param ctx
   * @param tree
   * @param block
   * @return
   */
  private LikeEval parseLike(Context ctx, Tree tree, QueryBlock block) {
    int idx = 0;
    
    boolean not = false;
    if (tree.getChild(idx).getType() == NQLParser.NOT) {
      not = true;
      idx++;
    }
    
    FieldEval field = (FieldEval) createEvalTree(ctx, tree.getChild(idx), block);
    idx++;
    ConstEval pattern = (ConstEval) createEvalTree(ctx, tree.getChild(idx), block);

    return new LikeEval(not, field, pattern);
  }
  
  /**
   * It parses the below EBNF.
   * <pre>
   * column_reference  
   * : fieldName (COMMA fieldName)* -> fieldName+
   * ;
   * </pre>
   * @param ctx
   * @param parent
   * @return
   */
  private Column [] parseColumnReferences(final Context ctx, 
      final CommonTree parent) {
    Column [] columns = new Column[parent.getChildCount()];
    for (int i = 0; i < columns.length; i++) {
      columns[i] = checkAndGetColumnByAST(ctx, (CommonTree) parent.getChild(i));
    }
    
    return columns;
  }
}
