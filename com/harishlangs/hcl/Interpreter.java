package com.harishlangs.hcl;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import com.harishlangs.hcl.std.*;

public class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
    private static class BreakException extends RuntimeException {}
    public Scanner stdin = new Scanner(System.in);

    final Environment globals = new Environment();
    private Environment environment = globals;
    private final Map<Expr, Integer> locals = new HashMap<>();

    private final Map<String, Class<?>> natImport = new HashMap<>();
    private final ArrayList<String> stdImport = new ArrayList<>();
    private final Map<String, Class<?>> modImport = new HashMap<>();
    private final ArrayList<String> imported = new ArrayList<>();


    Interpreter() {
      natImport.put("structures", HclStructures.class);

      // stdImport.add("<New Imports>");

      modImport.put("Math", HclMath.class);

      NativeModule HCLstd = new NativeModule();
      globals.importMod(HCLstd.getObjects());
    }

    void interpret(List<Stmt> statements) {
      try {
        for (Stmt statement : statements) {
          execute(statement);
        }
      } catch (RuntimeError error) {
        Hcl.runtimeError(error);
      }
    }

    String interpret(Expr expression) {
      try {
        Object value = evaluate(expression);
        return HclUtils.stringify(value);
      } catch (RuntimeError error) {
        Hcl.runtimeError(error);
        return null;
      }
    }

    @Override
    public Object visitLiteralExpr(Expr.Literal expr) {
        return expr.value;
    }

    @Override
    public Object visitLogicalExpr(Expr.Logical expr) {
      Object left = evaluate(expr.left);
    
      if (expr.operator.type == TokenType.OR) {
        if (HclUtils.isTruthy(left)) return left;
      } else {
        if (!HclUtils.isTruthy(left)) return left;
      }
    
      return evaluate(expr.right);
    }

    @Override
    public Object visitSetExpr(Expr.Set expr) {
      Object object = evaluate(expr.object);

      if (!(object instanceof HclInstance)) { 
        throw new RuntimeError(expr.name,"Only instances have fields.");
      }

      Object value = evaluate(expr.value);
      ((HclInstance)object).set(expr.name, value);
      return value;
    }

    @Override
    public Object visitSuperExpr(Expr.Super expr) {
      int distance = locals.get(expr);
      HclClass superclass = (HclClass)environment.getAt(
          distance, "super");

      HclInstance object = (HclInstance)environment.getAt(
        distance - 1, "self");

      HclFunction method = superclass.findMethod(expr.method.lexeme);

      if (method == null)
        throw new RuntimeError(expr.method,"Undefined property '" + expr.method.lexeme + "'.");

      return method.bind(object);
    }

    @Override
    public Object visitSelfExpr(Expr.Self expr) {
      return lookUpVariable(expr.keyword, expr);
    }

    @Override
    public Object visitUnaryExpr(Expr.Unary expr) {
        Object right = evaluate(expr.right);
        
        switch (expr.operator.type) {
            case BANG:
                return !HclUtils.isTruthy(right);
            case MINUS:
                HclUtils.checkNumberOperand(expr.operator, right);
                return -(double)right;
            default:
                // Do nothing
        }  

        // Unreachable.
        return null;
    }

    @Override
    public Object visitVariableExpr(Expr.Variable expr) {
      return lookUpVariable(expr.name, expr);
    }

    private Object lookUpVariable(Token name, Expr expr) {
      Integer distance = locals.get(expr);
      if (distance != null) {
        return environment.getAt(distance, name.lexeme);
      } else {
        return globals.get(name);
      }
    }

    @Override
    public Object visitGroupingExpr(Expr.Grouping expr) {
        return evaluate(expr.expression);
    }

    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }

    private void execute(Stmt stmt) {
      stmt.accept(this);
    }
    
    void resolve(Expr expr, int depth) {
      locals.put(expr, depth);
    }

    void executeBlock(List<Stmt> statements, Environment environment) {
      Environment previous = this.environment;
      try {
        this.environment = environment;

        for (Stmt statement : statements) {
          execute(statement);
        }
      } finally {
        this.environment = previous;
      }
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
      executeBlock(stmt.statements, new Environment(environment));
      return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
      Object superclass = null;
      if (stmt.superclass != null) {
        superclass = evaluate(stmt.superclass);
        if (!(superclass instanceof HclClass)) {
          throw new RuntimeError(stmt.superclass.name, "Superclass must be a class.");
        }
      }

      environment.define(stmt.name.lexeme, null);

      if (stmt.superclass != null) {
        environment = new Environment(environment);
        environment.define("super", superclass);
      }

      Map<String, HclFunction> classMethods = new HashMap<>();
      for (Stmt.Function method : stmt.classMethods) {
        HclFunction function = new HclFunction(method, environment, false);
        classMethods.put(method.name.lexeme, function);
      }
    
      HclClass metaclass = new HclClass(null, stmt.name.lexeme + " metaclass", (HclClass)superclass, classMethods);


      Map<String, HclFunction> methods = new HashMap<>();
      for (Stmt.Function method : stmt.methods) {
        HclFunction function = new HclFunction(method, environment, method.name.lexeme.equals("_init"));
        methods.put(method.name.lexeme, function);
      }

      HclClass klass = new HclClass(metaclass, stmt.name.lexeme, (HclClass)superclass, methods);

      if (superclass != null) {
        environment = environment.enclosing;
      }

      environment.assign(stmt.name, klass);
      return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
      evaluate(stmt.expression);
      return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
      HclFunction function = new HclFunction(stmt, environment, false);
      environment.define(stmt.name.lexeme, function);
      return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
      if (HclUtils.isTruthy(evaluate(stmt.condition))) {
        execute(stmt.thenBranch);
      } else if (stmt.elseBranch != null) {
        execute(stmt.elseBranch);
      }
      return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
      Object value = evaluate(stmt.expression);
      System.out.print(HclUtils.stringify(value));
      return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
      Object value = null;
      if (stmt.value != null) value = evaluate(stmt.value);
    
      throw new Return(value);
    }

    @Override
    public Void visitLetStmt(Stmt.Let stmt) {
      Object value = null;
      if (stmt.initializer != null) {
        value = evaluate(stmt.initializer);
      }

      environment.define(stmt.name.lexeme, value);
      return null;
    }

    @Override
    public Void visitLoopStmt(Stmt.Loop stmt) {
      try {
        while(true) {
          execute(stmt.body);
        }
      } catch (BreakException ex) {
        // Breaks Loop
      }
      return null;
    }

    @Override
    public Void visitBreakStmt(Stmt.Break stmt) {
      throw new BreakException();
    }

    @Override
    public Object visitAssignExpr(Expr.Assign expr) {
      Object value = evaluate(expr.value);
      
      Integer distance = locals.get(expr);
      if (distance != null) {
        environment.assignAt(distance, expr.name, value);
      } else {
        globals.assign(expr.name, value);
      }

      return value;
    }

    @Override
    public Object visitBinaryExpr(Expr.Binary expr) {
      Object left = evaluate(expr.left);
      Object right = evaluate(expr.right); 

      switch (expr.operator.type) {
        case GREATER:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left > (double)right;
        case GREATER_EQUAL:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left >= (double)right;
        case LESS:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left < (double)right;
        case LESS_EQUAL:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left <= (double)right;
        case MINUS:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left - (double)right;
        case PLUS:

          if (left instanceof Double && right instanceof Double) {
            return (double)left + (double)right;
          } 
  
          if (left instanceof String && right instanceof String) {
            return (String)left + (String)right;
          }

          if (left instanceof String && right instanceof Double) {
            return (String)left + HclUtils.stringify(right);
          }

          if (left instanceof Double && right instanceof String) {
            return HclUtils.stringify(left) + (String)right;
          }

          throw new RuntimeError(expr.operator,"Operands must be two numbers or two strings.");

        case SLASH:
            HclUtils.checkNumberOperands(expr.operator, left, right);
            return (double)left / (double)right;
        case STAR:
            if (left instanceof Double && right instanceof Double) {
              return (double)left * (double)right;
            }

            if (left instanceof String && right instanceof Double) {
              return HclUtils.repeatStr((String)left, (int)Math.floor((double)right));
              //return ((String)left).repeat((int)Math.floor((double)right));
            }

            if (left instanceof Double && right instanceof String) {
              return HclUtils.repeatStr((String)right, (int)Math.floor((double)left));
              // return ((String)right).repeat((int)Math.floor((double)left));
            }

            throw new RuntimeError(expr.operator,"Operands must be two numbers or any one can be strings.");
        
        case CARET:
          HclUtils.checkNumberOperands(expr.operator, left, right);
          return Math.pow((double)left, (double)right);

        case BANG_EQUAL: return !HclUtils.isEqual(left, right);
        case EQUAL: return HclUtils.isEqual(left, right);
        default:
        // Do nothing
      }

      // Unreachable.
      return null;
    }

    @Override
    public Object visitCallExpr(Expr.Call expr) {
      Object callee = evaluate(expr.callee);

      List<Object> arguments = new ArrayList<>();
      for (Expr argument : expr.arguments) { 
        arguments.add(evaluate(argument));
      }

      if (!(callee instanceof HclCallable)) {
        throw new RuntimeError(expr.paren,"Can only call functions and classes.");
      }

      HclCallable function = (HclCallable)callee;
      if ((arguments.size() != function.arity()) && !function.isVaArg()) {
        throw new RuntimeError(expr.paren, "Expected " +
            function.arity() + " arguments but got " +
            arguments.size() + ".");
      }
      return function.call(this, arguments);
    }

    @Override
    public Object visitGetExpr(Expr.Get expr) {
      Object object = evaluate(expr.object);
      if (object instanceof HclInstance) {
        return ((HclInstance) object).get(expr.name);
      }

      throw new RuntimeError(expr.name,
          "Only instances have properties.");
    }

    @SuppressWarnings("unchecked")
    @Override
    public Void visitImportStmt(Stmt.Import stmt) {
      Object module = evaluate(stmt.module);
      if (!(module instanceof String)) {
        throw new RuntimeError(stmt.keyword, "Expected a string.");
      }

      if (imported.contains((String)module)) {
        throw new RuntimeError(stmt.keyword, 
            String.format("Module %s is already imported.", (String)module));
      }

      if ((imported.contains("Std " + (String)module))) {
        throw new RuntimeError(stmt.keyword, 
            String.format("Standard Module %s is already imported.", (String)module));
      }

      if (!stmt.isStd) {
        HclUtils.importIt(stmt.keyword, (String)module + ".hcl");
        imported.add((String)module);
      } else {
        if (natImport.containsKey((String)module)) {
          try {
            Constructor<?> constructor = natImport.get((String)module).getConstructor();
            Object mod = constructor.newInstance();
            Method method = natImport.get((String)module).getMethod("getObjects");
            
            globals.importMod((Map<String, Object>) method.invoke(mod));
            imported.add("Std " + (String)module);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        } else if (modImport.containsKey((String)module)) {
          try {
            Constructor<?> constructor = modImport.get((String)module).getConstructor();
            Object mod = constructor.newInstance();
            
            globals.define((String)module, mod);
            imported.add("Std " + (String)module);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        } else if (stdImport.contains((String)module)) {
          String path = Hcl.homePath + File.separator + "std" + File.separator + (String)module + ".hcl";
          HclUtils.importIt(stmt.keyword, path);
          imported.add("Std " + (String)module);
        } else {
          throw new RuntimeError(stmt.keyword, 
            String.format("Can't import Standard Module %s.", (String)module));
        }
      }

      return null;
    }

}
