package jbse.apps;

import static jbse.apps.Util.LINE_SEP;
import static jbse.apps.Util.PATH_SEP;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import jbse.bc.ClassFile;
import jbse.bc.ClassHierarchy;
import jbse.common.Type;
import jbse.mem.Array;
import jbse.mem.Clause;
import jbse.mem.ClauseAssume;
import jbse.mem.ClauseAssumeReferenceSymbolic;
import jbse.mem.Frame;
import jbse.mem.Instance;
import jbse.mem.Klass;
import jbse.mem.Objekt;
import jbse.mem.SnippetFrameContext;
import jbse.mem.State;
import jbse.mem.Variable;
import jbse.mem.exc.ThreadStackEmptyException;
import jbse.val.Expression;
import jbse.val.FunctionApplication;
import jbse.val.MemoryPath;
import jbse.val.NarrowingConversion;
import jbse.val.Primitive;
import jbse.val.PrimitiveSymbolic;
import jbse.val.ReferenceSymbolic;
import jbse.val.Simplex;
import jbse.val.Value;
import jbse.val.WideningConversion;

/**
 * A {@link Formatter} which produces a complex, fully 
 * descriptive rendition of a {@link State}.
 * 
 * @author Pietro Braione
 */
public class StateFormatterText implements Formatter {
    protected List<String> srcPath;
    protected StringBuilder output = new StringBuilder();

    public StateFormatterText(List<String> srcPath) {
        this.srcPath = Collections.unmodifiableList(srcPath);
    }

    @Override
    public void formatState(State s) {
        formatState(s, this.output, this.srcPath, true, "\t", "");
    }

    @Override
    public String emit() {
        return this.output.toString();
    }

    @Override
    public void cleanup() {
        this.output = new StringBuilder();
    }

    private static void formatState(State state, StringBuilder sb, List<String> srcPath, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        sb.append(state.getIdentifier()); sb.append("["); sb.append(state.getSequenceNumber()); sb.append("] "); sb.append(lineSep);
        if (state.isStuck()) {
            sb.append("Leaf state");
            if (state.getStuckException() != null) {
                sb.append(", raised exception: "); sb.append(state.getStuckException().toString());
            } else if (state.getStuckReturn() != null) {
                sb.append(", returned value: "); sb.append(state.getStuckReturn().toString());
            } //else, append nothing
            sb.append(lineSep);
        } else {
            try {
                sb.append("Method signature: "); sb.append(state.getCurrentMethodSignature()); sb.append(lineSep);
                sb.append("Program counter: "); sb.append(state.getPC()); sb.append(lineSep);
                final BytecodeFormatter bfmt = new BytecodeFormatter();
                sb.append("Next bytecode: "); sb.append(bfmt.format(state)); sb.append(lineSep); 
                sb.append("Source line: "); sourceLine(state.getCurrentFrame(), sb, srcPath); sb.append(lineSep);
            } catch (ThreadStackEmptyException e) {
                //the state is not stuck but it has no frames:
                //this case is not common but it can mean a state
                //not completely ready to run
            }
        }
        sb.append("Path condition: "); formatPathCondition(state, sb, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep);
        sb.append("Static store: {"); sb.append(lineSep); formatStaticMethodArea(state, sb, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep); sb.append("}"); sb.append(lineSep);
        sb.append("Heap: {"); sb.append(lineSep); formatHeap(state, sb, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep); sb.append("}"); sb.append(lineSep);
        if (state.getStackSize() > 0) {
            sb.append("Stack: {"); sb.append(lineSep); formatStack(state, sb, srcPath, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep); sb.append("}");
        }
        sb.append(lineSep);
    }
    
    private static void formatPathCondition(State s, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        final StringBuilder expression = new StringBuilder();
        final StringBuilder where = new StringBuilder();
        boolean doneFirstExpression = false;
        boolean doneFirstWhere = false;
        HashSet<String> doneSymbols = new HashSet<String>();
        for (Clause c : s.getPathCondition()) {
            expression.append(doneFirstExpression ? (" &&" + lineSep) : ""); expression.append(indentCurrent);
            doneFirstExpression = true;
            if (c instanceof ClauseAssume) {
                final Primitive cond = ((ClauseAssume) c).getCondition();
                formatValue(s, expression, cond);
                final StringBuilder expressionFormatted = new StringBuilder();
                formatPrimitiveForPathCondition(cond, expressionFormatted, breakLines, indentTxt, indentCurrent, doneSymbols);
                if (expressionFormatted.length() == 0) {
                    //does nothing
                } else {
                    where.append(doneFirstWhere ? (" &&" + lineSep) : ""); where.append(indentCurrent); where.append(expressionFormatted);
                    doneFirstWhere = true;
                }
            } else if (c instanceof ClauseAssumeReferenceSymbolic) {
                final ReferenceSymbolic ref = ((ClauseAssumeReferenceSymbolic) c).getReference(); 
                expression.append(ref.toString()); expression.append(" == ");
                if (s.isNull(ref)) {
                    expression.append("null");
                } else {
                    final MemoryPath tgtOrigin = s.getObject(ref).getOrigin();
                    expression.append("Object["); expression.append(s.getResolution(ref)); expression.append("] ("); expression.append(ref.getOrigin().equals(tgtOrigin) ? "fresh" : ("aliases " + tgtOrigin)); expression.append(")");
                }
                final String referenceFormatted = formatReferenceForPathCondition(ref, doneSymbols); 
                if (referenceFormatted.equals("")) {
                    //does nothing
                } else {
                    if (doneFirstWhere) {
                        where.append(" &&"); where.append(lineSep);
                    }
                    where.append(indentCurrent); where.append(referenceFormatted);
                    doneFirstWhere = true;
                }
            } else { //(c instanceof ClauseAssumeClassInitialized) || (c instanceof ClauseAssumeClassNotInitialized)
                expression.append(c.toString());
            }
        }
        if (expression.length() > 0) {
            sb.append(lineSep); sb.append(expression);
        }
        if (where.length() > 0) {
            sb.append(lineSep); sb.append(indentCurrent); sb.append("where:"); sb.append(lineSep); sb.append(where);
        }
    }

    private static String formatReferenceForPathCondition(ReferenceSymbolic r, HashSet<String> done) {
        if (done.contains(r.toString())) {
            return "";
        } else {
            return r.toString() + " == " + r.getOrigin();
        }
    }

    private static boolean formatExpressionForPathCondition(Expression e, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent, HashSet<String> done) {
        final Primitive firstOp = e.getFirstOperand();
        final Primitive secondOp = e.getSecondOperand();
        boolean someFirstOp = false;
        if (firstOp != null) {
            someFirstOp = formatPrimitiveForPathCondition(firstOp, sb, breakLines, indentTxt, indentCurrent, done);
        }
        final StringBuilder second = new StringBuilder();
        final boolean someSecondOp = formatPrimitiveForPathCondition(secondOp, second, breakLines, indentTxt, indentCurrent, done);
        if (!someFirstOp || !someSecondOp) {
            //does nothing
        } else {
            final String lineSep = (breakLines ? LINE_SEP : "");
            sb.append(" &&"); sb.append(lineSep); sb.append(indentCurrent);
        }
        sb.append(second);
        return (someFirstOp || someSecondOp);
    }

    private static boolean formatPrimitiveForPathCondition(Primitive p, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent, HashSet<String> done) {
        if (p instanceof Expression) {
            return formatExpressionForPathCondition((Expression) p, sb, breakLines, indentTxt, indentCurrent, done);
        } else if (p instanceof PrimitiveSymbolic) {
            if (done.contains(p.toString())) {
                return false;
            } else {
                done.add(p.toString());
                sb.append(p.toString()); sb.append(" == "); sb.append(((PrimitiveSymbolic) p).getOrigin());
                return true;
            }
        } else if (p instanceof FunctionApplication) {
            return formatFunctionApplicationForPathCondition((FunctionApplication) p, sb, breakLines, indentTxt, indentCurrent, done);
        } else if (p instanceof WideningConversion) {
            final WideningConversion pWiden = (WideningConversion) p;
            return formatPrimitiveForPathCondition(pWiden.getArg(), sb, breakLines, indentTxt, indentCurrent, done);
        } else if (p instanceof NarrowingConversion) {
            final NarrowingConversion pNarrow = (NarrowingConversion) p;
            return formatPrimitiveForPathCondition(pNarrow.getArg(), sb, breakLines, indentTxt, indentCurrent, done);
        } else { //(p instanceof Any || p instanceof Simplex)
            return false;
        }
    }

    private static boolean formatFunctionApplicationForPathCondition(FunctionApplication a, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent, HashSet<String> done) {
        boolean first = true;
        boolean some = false;
        final String lineSep = (breakLines ? LINE_SEP : "");
        for (Primitive p : a.getArgs()) {
            final StringBuilder arg = new StringBuilder();
            final boolean argSome = formatPrimitiveForPathCondition(p, arg, breakLines, indentTxt, indentCurrent, done);
            some = some || argSome;
            if (argSome) {
                //does nothing
            } else { 
                if (!first) {
                    sb.append(" &&"); sb.append(lineSep); sb.append(indentCurrent);
                }
                sb.append(arg);
                first = false;
            }
        }
        return some;
    }

    private static void formatHeap(State s, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        final Map<Long, Objekt> h = s.getHeap();
        final int heapSize = h.size();
        sb.append(indentCurrent);
        int j = 0;
        for (Map.Entry<Long, Objekt> e : h.entrySet()) {
            Objekt o = e.getValue();
            sb.append("Object["); sb.append(e.getKey()); sb.append("]: "); sb.append("{");
            formatObject(s, sb, o, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep);
            sb.append(indentCurrent); sb.append("}");
            if (j < heapSize - 1) {
                sb.append(lineSep); sb.append(indentCurrent);
            }
            j++;
        }
    }

    private static void formatStaticMethodArea(State state, StringBuilder sb, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        final Map<ClassFile, Klass> a = state.getStaticMethodArea();
        sb.append(indentCurrent);
        boolean doneFirst = false;
        for (Map.Entry<ClassFile, Klass> ee : a.entrySet()) {
            final Klass k = ee.getValue();
            if (k.getStoredFieldSignatures().size() > 0) { //only klasses with fields will be printed
                if (doneFirst) {
                    sb.append(lineSep); sb.append(indentCurrent);
                }
                doneFirst = true;
                final ClassFile c = ee.getKey();
                sb.append("Class[("); sb.append(c.getDefiningClassLoader()); sb.append(", "); sb.append(c.getClassName()); sb.append(")]: {");
                formatObject(state, sb, k, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep);
                sb.append(indentCurrent); sb.append("}");
            }
        }
    }

    private static void formatObject(State s, StringBuilder sb, Objekt o, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        if (o.getOrigin() != null) {
            sb.append(lineSep); sb.append(indentCurrent); sb.append("Origin: "); sb.append(o.getOrigin());
        }
        //explicit dispatch on type
        if (o instanceof Array) {
            final Array a = (Array) o;
            formatArray(s, sb, a, breakLines, indentTxt, indentCurrent);
        } else if (o instanceof Instance) {
            final Instance i = (Instance) o;
            formatInstance(s, sb, i, breakLines, indentTxt, indentCurrent);
        } else if (o instanceof Klass) {
            final Klass k = (Klass) o;
            formatKlass(s, sb, k, breakLines, indentTxt, indentCurrent);
        }
    }

    private static void formatArray(State s, StringBuilder sb, Array a, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        if (a.isSymbolic() && a.isInitial()) {
            sb.append(" (initial)");
        }
        sb.append(lineSep); sb.append(indentCurrent); sb.append("Type: "); sb.append(a.getType());
        sb.append(lineSep); sb.append(indentCurrent); sb.append("Length: "); sb.append(a.getLength()); 
        sb.append(lineSep); sb.append(indentCurrent); sb.append("Items: {");
        final String indentOld = indentCurrent;
        if (!a.hasSimpleRep()) {
            indentCurrent += indentTxt;
        }
        //if it is an array of chars, then it prints it in a string style
        final boolean printAsString = a.isSimple() && (a.getType().getMemberClass().getClassName().equals("char"));
        if (printAsString) {
            sb.append("\"");
        }
        boolean skipComma = true;
        boolean hasUnknownValues = false;
        boolean hasKnownValues = false;
        final StringBuilder buf = new StringBuilder();
        for (Array.AccessOutcomeIn e : a.values()) {
            if (a.hasSimpleRep()) {
                hasKnownValues = true; //the entries will surely have values
                buf.append(skipComma ? "" : ", ");
                formatArrayEntry(s, buf, e, false);
                if (!printAsString) {
                    skipComma = false;
                }
            } else {
                final StringBuilder entryFormatted = new StringBuilder();
                final boolean nothing = formatArrayEntry(s, entryFormatted, e, true);
                if (nothing) {
                    hasUnknownValues = true;
                } else {
                    hasKnownValues = true;
                    buf.append(lineSep);
                    buf.append(indentCurrent);
                    buf.append(entryFormatted);
                }
            }
        }
        sb.append(buf);
        if (printAsString) {
            sb.append("\"");
        }
        if (!a.hasSimpleRep()) {
            if (hasUnknownValues) {
                sb.append(lineSep); sb.append(indentCurrent); sb.append("(no assumption on "); sb.append(hasKnownValues ? "other " : ""); sb.append("values)");
            }
            sb.append(lineSep);
            indentCurrent = indentOld;
            sb.append(indentCurrent);
        }
        sb.append("}");
    }

    private static boolean formatArrayEntry(State s, StringBuilder sb, Array.AccessOutcomeIn e, boolean showExpression) {
        final StringBuilder val = new StringBuilder();
        if (e instanceof Array.AccessOutcomeInValue) {
            final Array.AccessOutcomeInValue eCast = (Array.AccessOutcomeInValue) e; 
            if (eCast.getValue() == null) {
                return true;
            }
            formatValue(s, val, eCast.getValue());
        } else {
            final Array.AccessOutcomeInInitialArray eCast = (Array.AccessOutcomeInInitialArray) e;
            val.append(eCast.getInitialArray().toString()); val.append("[_ + "); val.append(eCast.getOffset()); val.append("]");
        }
        if (showExpression) {
            formatValue(s, sb, e.getAccessCondition());
            sb.append(" -> ");
        }
        sb.append(val);
        return false;
    }

    private static void formatInstance(State s, StringBuilder sb, Instance i, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        sb.append(lineSep);
        sb.append(indentCurrent);
        sb.append("Class: ");
        sb.append(i.getType());
        int z = 0;
        for (Map.Entry<String, Variable> e : i.fields().entrySet()) {
            sb.append(lineSep);
            sb.append(indentCurrent);
            sb.append("Field[");
            sb.append(z);
            sb.append("]: ");
            formatVariable(s, sb, e.getValue());
            ++z;
        }
    }

    private static void formatKlass(State s, StringBuilder sb, Klass k, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        sb.append(lineSep);
        int z = 0;
        for (Map.Entry<String, Variable> e : k.fields().entrySet()) {
            if (z > 0) {
                sb.append(lineSep);
            }
            sb.append(indentCurrent);
            sb.append("Field[");
            sb.append(z);
            sb.append("]: ");
            formatVariable(s, sb, e.getValue());
            ++z;
        }
    }

    private static void formatVariable(State s, StringBuilder sb, Variable v) {
        sb.append("Name: "); sb.append(v.getName()); sb.append(", Type: "); sb.append(v.getType()); sb.append(", Value: ");
        final Value val = v.getValue(); 
        if (val == null) {
            sb.append("ERROR: no value has been assigned to this variable.");
        } else {
            formatValue(s, sb, val); sb.append(" "); formatType(sb, val);
        }
    }

    private static void formatValue(State s, StringBuilder sb, Value val) {
        if (val.getType() == Type.CHAR && val instanceof Simplex) {
            final char c = ((Character) ((Simplex) val).getActualValue()).charValue();
            if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\"') {
                sb.append("\\\"");
            } else if (c == '\'') {
                sb.append("\\\'");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\u0000') {
                sb.append("\\u0000");
            } else {
                sb.append(c);
            }
        } else {
            sb.append(val.toString());
        } 
        if (val instanceof ReferenceSymbolic) {
            final ReferenceSymbolic ref = (ReferenceSymbolic) val;
            if (s.resolved(ref)) {
                if (s.isNull(ref)) {
                    sb.append(" == null");
                } else {
                    sb.append(" == Object["); sb.append(s.getResolution(ref)); sb.append("]");
                }
            }
        }
    }

    private static void formatType(StringBuilder sb, Value val) {
        sb.append("(type: "); sb.append(val.getType()); sb.append(")");		
    }

    private static void formatStack(State s, StringBuilder sb, List<String> srcPath, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        final Iterable<Frame> stack = s.getStack();
        final int size = s.getStackSize();
        sb.append(indentCurrent);
        int j = 0;
        for (Frame f : stack) {
            sb.append("Frame["); sb.append(j); sb.append("]: {"); sb.append(lineSep); 
            formatFrame(s, sb, f, srcPath, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep);
            sb.append(indentCurrent); sb.append("}");
            if (j < size - 1) 
                sb.append(lineSep); sb.append(indentCurrent);
            j++;
        }
    }

    private static void formatFrame(State s, StringBuilder sb, Frame f, List<String> srcPath, boolean breakLines, String indentTxt, String indentCurrent) {
        final String lineSep = (breakLines ? LINE_SEP : "");
        sb.append(indentCurrent); sb.append("Method signature: "); sb.append(f.getCurrentMethodSignature().toString());
        if (f instanceof SnippetFrameContext) {
            sb.append(" (executing snippet, will resume with program counter "); sb.append(((SnippetFrameContext) f).getContextFrame().getReturnProgramCounter()); sb.append(")");
        }
        sb.append(lineSep);
        sb.append(indentCurrent); sb.append("Program counter: "); sb.append(f.getProgramCounter()); sb.append(lineSep);
        sb.append(indentCurrent); sb.append("Program counter after return: "); 
        sb.append((f.getReturnProgramCounter() == Frame.UNKNOWN_PC) ? "<UNKNOWN>" : f.getReturnProgramCounter()); sb.append(lineSep);
        final ClassHierarchy hier = s.getClassHierarchy();
        final BytecodeFormatter bfmt = new BytecodeFormatter();
        sb.append(indentCurrent); sb.append("Next bytecode: "); sb.append(bfmt.format(f, hier)); sb.append(lineSep); 
        sb.append(indentCurrent); sb.append("Source line: "); sourceLine(f, sb, srcPath); sb.append(lineSep);
        sb.append(indentCurrent); sb.append("Operand Stack: {"); sb.append(lineSep); formatOperandStack(s, sb, f, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep); sb.append(indentCurrent); sb.append("}"); sb.append(lineSep);
        sb.append(indentCurrent); sb.append("Local Variables: {"); sb.append(lineSep); formatLocalVariables(s, sb, f, breakLines, indentTxt, indentCurrent + indentTxt); sb.append(lineSep); sb.append(indentCurrent); sb.append("}");
    }

    private static void formatOperandStack(State s, StringBuilder sb, Frame f, boolean breakLines, String indentTxt, String indentCurrent) {
        sb.append(indentCurrent);
        final String lineSep = (breakLines ? LINE_SEP : "");
        int i = 0;
        final int last = f.values().size() - 1;
        for (Value v : f.values()) {
            sb.append("Operand[");
            sb.append(i);
            sb.append("]: ");
            formatValue(s, sb, v);
            sb.append(" ");
            formatType(sb, v);
            if (i < last)  {
                sb.append(lineSep);
                sb.append(indentCurrent);
            }
            ++i;
        }
    }

    private static void formatLocalVariables(State s, StringBuilder sb, Frame f, boolean breakLines, String indentTxt, String indentCurrent) {
        sb.append(indentCurrent);
        boolean isFirst = true;
        final Map<Integer, Variable> lva = f.localVariables();
        final TreeSet<Integer> slots = new TreeSet<>(lva.keySet());
        final String lineSep = (breakLines ? LINE_SEP : "");
        for (int i : slots) {
            if (isFirst) {
                isFirst = false;
            } else {
                sb.append(lineSep);
                sb.append(indentCurrent);
            }
            sb.append("Variable[");
            sb.append(i);
            sb.append("]: ");
            formatVariable(s, sb, lva.get(i));
        }
    }

    private static void sourceLine(Frame f, StringBuilder sb, List<String> srcPath) {
        int sourceRow = f.getSourceRow();
        if (sourceRow == -1) { 
            sb.append("<UNKNOWN>");
        } else { 
            sb.append("("); sb.append(sourceRow); sb.append("): ");
            final String row;
            if (srcPath == null) {
                row = null;
            } else {
                row = Util.getSrcFileRow(f.getCurrentMethodSignature().getClassName(), srcPath, PATH_SEP, sourceRow);
            }
            sb.append(row == null ? "<UNKNOWN>" : row);
        }
    }
}
