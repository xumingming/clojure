/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 31, 2007 */

package clojure.lang;

import java.util.concurrent.atomic.AtomicBoolean;


public final class Var extends ARef implements IFn, IRef, Settable{

/**
 * 该类表示Var实际包含的值
 */
static class TBox{
/**
 * var所指向的值
 */
volatile Object val;
/**
 * 绑定的线程 -- 对于动态var
 */
final Thread thread;

public TBox(Thread t, Object val){
	this.thread = t;
	this.val = val;
}
}

static public class Unbound extends AFn{
	final public Var v;

	public Unbound(Var v){
		this.v = v;
	}

	public String toString(){
		return "Unbound: " + v;
	}

	public Object throwArity(int n){
		throw new IllegalStateException("Attempting to call unbound fn: " + v);
	}
}

/**
 * Frame貌似是因为作用域而存在的
 * 作用域没往下深一层，就会向Var.dvals里面push一个新的Frame，而新Frame的prev就是上一个Frame
 * 这样在作用域往上退的时候只要pop掉前面那个Frame就可以了
 */
static class Frame{
	//Var->TBox
    /**
     * 当前的所有绑定: Var => TBox
     */
	Associative bindings;
	//Var->val
//	Associative frameBindings;
	/**
	 * 上一层作用域的所有绑定
	 */
	Frame prev;


	public Frame(){
		this(PersistentHashMap.EMPTY, null);
	}

	public Frame(Associative bindings, Frame prev){
//		this.frameBindings = frameBindings;
		this.bindings = bindings;
		this.prev = prev;
	}

    	protected Object clone() {
		Frame f = new Frame();
		f.bindings = this.bindings;
		return f;
    	}

}

/**
 * 保存的是动态var？
 */
static final ThreadLocal<Frame> dvals = new ThreadLocal<Frame>(){

	protected Frame initialValue(){
		return new Frame();
	}
};

/**
 * rev应该是revision的意思，var没变更一次它加一
 */
static public volatile int rev = 0;

static Keyword privateKey = Keyword.intern(null, "private");
static IPersistentMap privateMeta = new PersistentArrayMap(new Object[]{privateKey, Boolean.TRUE});
static Keyword macroKey = Keyword.intern(null, "macro");
static Keyword nameKey = Keyword.intern(null, "name");
static Keyword nsKey = Keyword.intern(null, "ns");
//static Keyword tagKey = Keyword.intern(null, "tag");

/**
 * 根绑定
 */
volatile Object root;
/**
 * 是不是动态var
 */
volatile boolean dynamic = false;
/**
 * 这个var在当前线程是否有绑定？
 */
transient final AtomicBoolean threadBound;
/**
 * 这个var的名字
 */
public final Symbol sym;
/**
 * 这个var的名字空间
 */
public final Namespace ns;

//IPersistentMap _meta;

/**
 * 获取当前线程的Frame
 * @return
 */
public static Object getThreadBindingFrame(){
	Frame f = dvals.get();
	if(f != null)
		return f;
	return new Frame();
}

/**
 * 克隆当前线程的Frame
 * @return
 */
public static Object cloneThreadBindingFrame(){
	Frame f = dvals.get();
	if(f != null)
		return f.clone();
	return new Frame();
}

/**
 * 重置当前线程的Frame
 * @param frame
 */
public static void resetThreadBindingFrame(Object frame){
	dvals.set((Frame) frame);
}

public Var setDynamic(){
	this.dynamic = true;
	return this;
}

public Var setDynamic(boolean b){
	this.dynamic = b;
	return this;
}

public final boolean isDynamic(){
	return dynamic;
}

public static Var intern(Namespace ns, Symbol sym, Object root){
	return intern(ns, sym, root, true);
}

public static Var intern(Namespace ns, Symbol sym, Object root, boolean replaceRoot){
	Var dvout = ns.intern(sym);
	if(!dvout.hasRoot() || replaceRoot)
		dvout.bindRoot(root);
	return dvout;
}


public String toString(){
	if(ns != null)
		return "#'" + ns.name + "/" + sym;
	return "#<Var: " + (sym != null ? sym.toString() : "--unnamed--") + ">";
}

public static Var find(Symbol nsQualifiedSym){
	if(nsQualifiedSym.ns == null)
		throw new IllegalArgumentException("Symbol must be namespace-qualified");
	Namespace ns = Namespace.find(Symbol.intern(nsQualifiedSym.ns));
	if(ns == null)
		throw new IllegalArgumentException("No such namespace: " + nsQualifiedSym.ns);
	return ns.findInternedVar(Symbol.intern(nsQualifiedSym.name));
}

public static Var intern(Symbol nsName, Symbol sym){
	Namespace ns = Namespace.findOrCreate(nsName);
	return intern(ns, sym);
}

public static Var internPrivate(String nsName, String sym){
	Namespace ns = Namespace.findOrCreate(Symbol.intern(nsName));
	Var ret = intern(ns, Symbol.intern(sym));
	ret.setMeta(privateMeta);
	return ret;
}

public static Var intern(Namespace ns, Symbol sym){
	return ns.intern(sym);
}


public static Var create(){
	return new Var(null, null);
}

public static Var create(Object root){
	return new Var(null, null, root);
}

Var(Namespace ns, Symbol sym){
	this.ns = ns;
	this.sym = sym;
	this.threadBound = new AtomicBoolean(false);
	this.root = new Unbound(this);
	setMeta(PersistentHashMap.EMPTY);
}

Var(Namespace ns, Symbol sym, Object root){
	this(ns, sym);
	this.root = root;
	++rev;
}

/**
 * 这个var有没有绑定到一个值？
 * @return
 */
public boolean isBound(){
	return hasRoot() || (threadBound.get() && dvals.get().bindings.containsKey(this));
}

final public Object get(){
    // 如果没有当前线程绑定的值，那么返回根绑定
	if(!threadBound.get())
		return root;
	// 否则返回线程绑定值
	return deref();
}

final public Object deref(){
    // 取出跟当前线程绑定的值
	TBox b = getThreadBinding();
	if(b != null)
		return b.val;
	// 作为fallback，返回根绑定
	return root;
}

public void setValidator(IFn vf){
	if(hasRoot())
		validate(vf, root);
	validator = vf;
}

public Object alter(IFn fn, ISeq args) {
	set(fn.applyTo(RT.cons(deref(), args)));
	return this;
}

public Object set(Object val){
	validate(getValidator(), val);
	TBox b = getThreadBinding();
	if(b != null)
		{
		if(Thread.currentThread() != b.thread)
			throw new IllegalStateException(String.format("Can't set!: %s from non-binding thread", sym));
		return (b.val = val);
		}
	throw new IllegalStateException(String.format("Can't change/establish root binding of: %s with set", sym));
}

public Object doSet(Object val)  {
    return set(val);
    }

public Object doReset(Object val)  {
    bindRoot(val);
    return val;
    }

public void setMeta(IPersistentMap m) {
    //ensure these basis keys
    resetMeta(m.assoc(nameKey, sym).assoc(nsKey, ns));
}

public void setMacro() {
    try
        {
        alterMeta(assoc, RT.list(macroKey, RT.T));
        }
    catch (Exception e)
        {
        throw Util.sneakyThrow(e);
        }
}

/**
 * 这个var所指向的是一个宏么？
 * @return
 */
public boolean isMacro(){
	return RT.booleanCast(meta().valAt(macroKey));
}

//public void setExported(boolean state){
//	_meta = _meta.assoc(privateKey, state);
//}

/**
 * 这个var是public的么
 * @return
 */
public boolean isPublic(){
	return !RT.booleanCast(meta().valAt(privateKey));
}

/**
 * 返回root
 * @return
 */
final public Object getRawRoot(){
		return root;
}

/**
 * 返回tag
 * @return
 */
public Object getTag(){
	return meta().valAt(RT.TAG_KEY);
}

/**
 * 设置tag
 * @param tag
 */
public void setTag(Symbol tag) {
    try
        {
        alterMeta(assoc, RT.list(RT.TAG_KEY, tag));
        }
    catch (Exception e)
        {
        throw Util.sneakyThrow(e);
        }
}

/**
 * 这个var是否有根绑定
 * @return
 */
final public boolean hasRoot(){
	return !(root instanceof Unbound);
}

//binding root always clears macro flag
/**
 * 绑定一个新根绑定，并且去掉宏meta
 * @param root
 */
synchronized public void bindRoot(Object root){
	validate(getValidator(), root);
	Object oldroot = this.root;
	this.root = root;
	++rev;
    try
        {
        alterMeta(dissoc, RT.list(macroKey));
        }
    catch (Exception e)
        {
        throw Util.sneakyThrow(e);
        }
    notifyWatches(oldroot,this.root);
}

/**
 * 绑定一个新根绑定，不去掉宏meta
 * @param root
 */
synchronized void swapRoot(Object root){
	validate(getValidator(), root);
	Object oldroot = this.root;
	this.root = root;
	++rev;
    notifyWatches(oldroot,root);
}

synchronized public void unbindRoot(){
	this.root = new Unbound(this);
	++rev;
}

synchronized public void commuteRoot(IFn fn) {
	Object newRoot = fn.invoke(root);
	validate(getValidator(), newRoot);
	Object oldroot = root;
	this.root = newRoot;
	++rev;
    notifyWatches(oldroot,newRoot);
}

synchronized public Object alterRoot(IFn fn, ISeq args) {
	Object newRoot = fn.applyTo(RT.cons(root, args));
	validate(getValidator(), newRoot);
	Object oldroot = root;
	this.root = newRoot;
	++rev;
    notifyWatches(oldroot,newRoot);
	return newRoot;
}

/**
 * 作用域往下走的时候会调用这个方法
 * @param bindings
 */
public static void pushThreadBindings(Associative bindings){
	Frame f = dvals.get();
	Associative bmap = f.bindings;
	for(ISeq bs = bindings.seq(); bs != null; bs = bs.next())
		{
		IMapEntry e = (IMapEntry) bs.first();
		Var v = (Var) e.key();
		if(!v.dynamic)
			throw new IllegalStateException(String.format("Can't dynamically bind non-dynamic var: %s/%s", v.ns, v.sym));
		v.validate(v.getValidator(), e.val());
		v.threadBound.set(true);
		bmap = bmap.assoc(v, new TBox(Thread.currentThread(), e.val()));
		}
	dvals.set(new Frame(bmap, f));
}

/**
 * 作用域往上走的时候调用这个方法
 */
public static void popThreadBindings(){
	Frame f = dvals.get();
	if(f.prev == null)
		throw new IllegalStateException("Pop without matching push");
	dvals.set(f.prev);
}

/**
 * 获取当前线程的所有线程绑定
 * @return
 */
public static Associative getThreadBindings(){
	Frame f = dvals.get();
	IPersistentMap ret = PersistentHashMap.EMPTY;
	for(ISeq bs = f.bindings.seq(); bs != null; bs = bs.next())
		{
		IMapEntry e = (IMapEntry) bs.first();
		Var v = (Var) e.key();
		TBox b = (TBox) e.val();
		ret = ret.assoc(v, b.val);
		}
	return ret;
}

/**
 * 获取这个var跟当前线程的绑定
 * @return
 */
public final TBox getThreadBinding(){
	if(threadBound.get())
		{
		IMapEntry e = dvals.get().bindings.entryAt(this);
		if(e != null)
			return (TBox) e.val();
		}
	return null;
}

/**
 * 把deref()的返回值强制转型为IFn类型
 * @return
 */
final public IFn fn(){
	return (IFn) deref();
}

/**
 * 把deref()的返回值强制转型为IFn类型，然后再调用
 * @return
 */
public Object call() {
	return invoke();
}

/**
 * 跟invoke一样，只是没有返回值
 */
public void run(){
	try
		{
		invoke();
		}
	catch(Exception e)
		{
		throw Util.sneakyThrow(e);
		}
}

public Object invoke() {
	return fn().invoke();
}

public Object invoke(Object arg1) {
	return fn().invoke(arg1);
}

public Object invoke(Object arg1, Object arg2) {
	return fn().invoke(arg1, arg2);
}

public Object invoke(Object arg1, Object arg2, Object arg3) {
	return fn().invoke(arg1, arg2, arg3);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
	return fn().invoke(arg1, arg2, arg3, arg4);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7)
		{
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13)
		{
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14)
		{
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16, arg17);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16, arg17, arg18);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) {
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16, arg17, arg18, arg19);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20)
		{
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16, arg17, arg18, arg19, arg20);
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20,
                     Object... args)
		{
	return fn().invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
	                   arg16, arg17, arg18, arg19, arg20, args);
}

public Object applyTo(ISeq arglist) {
	return AFn.applyToHelper(this, arglist);
}

static IFn assoc = new AFn(){
    @Override
    public Object invoke(Object m, Object k, Object v)  {
        return RT.assoc(m, k, v);
    }
};
static IFn dissoc = new AFn() {
    @Override
    public Object invoke(Object c, Object k)  {
	    try
		    {
		    return RT.dissoc(c, k);
		    }
	    catch(Exception e)
		    {
            throw Util.sneakyThrow(e);
		    }
    }
};
}
