/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jan 23, 2008 */

package clojure.lang;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class Namespace extends AReference implements Serializable {
/**
 * 名字空间的名字
 */
final public Symbol name;
/**
 * 保存当前名字空间内 符号到“对象”的映射，这个对象可以是
 * <li>Var</li>
 * <li>Class, see {@link #referenceClass(Symbol, Class)}</li>
 */
transient final AtomicReference<IPersistentMap> mappings = new AtomicReference<IPersistentMap>();
/**
 * 保存从符号到名字空间对象的映射，表示在当前名字空间里面我们可以用简单符号去引用对应的名字空间
 */
transient final AtomicReference<IPersistentMap> aliases = new AtomicReference<IPersistentMap>();
/**
 * 从名字空间名字到名字空间对象本身的映射，表示系统当前所有的名字空间
 */
final static ConcurrentHashMap<Symbol, Namespace> namespaces = new ConcurrentHashMap<Symbol, Namespace>();

public String toString(){
	return name.toString();
}

Namespace(Symbol name){
	super(name.meta());
	this.name = name;
	mappings.set(RT.DEFAULT_IMPORTS);
	aliases.set(RT.map());
}

/**
 * 返回系统当前的所有名字空间对象
 * @return
 */
public static ISeq all(){
	return RT.seq(namespaces.values());
}

/**
 * 返回当前名字空间的名字
 * @return
 */
public Symbol getName(){
	return name;
}

/**
 * 返回当前名字空间的所有var映射
 */
public IPersistentMap getMappings(){
	return mappings.get();
}

/**
 * 什么是intern？所谓的intern就是？？？
 * @param sym
 * @return
 */
public Var intern(Symbol sym){
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	Object o;
	Var v = null;
	// 这里在持续的重试直到把这个新的var加到mapping里面去
	// 为什么这里要重试？因为可能有多个线程同时在操作这个mapping
	while((o = map.valAt(sym)) == null)
		{
		if(v == null)
			v = new Var(this, sym);
		IPersistentMap newMap = map.assoc(sym, v);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
	// 如果确实搞定了，那么直接返回
	if(o instanceof Var && ((Var) o).ns == this)
		return (Var) o;

	// 这里v怎么会为null? 唯一的可能是上面的while循环没有执行
	// 也就是说这个sym本来就有对应的值，所以while循环进不去
	if(v == null)
		v = new Var(this, sym);

	// 打印警告信息，因为原来sym是指向o的，现在我们要把它指向v
	warnOrFailOnReplace(sym, o, v);

	// 到这里v肯定不为null，不停的重试知道把v设置到mapping里面位置
	while(!mappings.compareAndSet(map, map.assoc(sym, v)))
		map = getMappings();

	return v;
}

/**
 * 检查一下o的正确性
 * @param sym
 * @param o
 * @param v
 */
private void warnOrFailOnReplace(Symbol sym, Object o, Object v){
    if (o instanceof Var)
        {
        Namespace ns = ((Var)o).ns;
        if (ns == this)
            return;
        if (ns != RT.CLOJURE_NS)
            throw new IllegalStateException(sym + " already refers to: " + o + " in namespace: " + name);
        }
	RT.errPrintWriter().println("WARNING: " + sym + " already refers to: " + o + " in namespace: " + name
		+ ", being replaced by: " + v);
}

/**
 * reference的实现跟intern很类似，区别是
 *   intern是把sym指向了一个new Var(this, sym), reference是把sym指向了传入参数val
 * @param sym
 * @param val
 * @return
 */
Object reference(Symbol sym, Object val){
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	Object o;
	while((o = map.valAt(sym)) == null)
		{
		IPersistentMap newMap = map.assoc(sym, val);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
	if(o == val)
		return o;

	warnOrFailOnReplace(sym, o, val);

	while(!mappings.compareAndSet(map, map.assoc(sym, val)))
		map = getMappings();

	return val;

}

/**
 * 是来自同一个名字的两个不同实例的Class?
 * @param cls1
 * @param cls2
 * @return
 */
public static boolean areDifferentInstancesOfSameClassName(Class cls1, Class cls2) {
    return (cls1 != cls2) && (cls1.getName().equals(cls2.getName()));
}

/**
 * 这个应该就是(import)函数所调用的方法
 * 
 * @param sym
 * @param val
 * @return
 */
Class referenceClass(Symbol sym, Class val){
    if(sym.ns != null)
        {
        throw new IllegalArgumentException("Can't intern namespace-qualified symbol");
        }
    IPersistentMap map = getMappings();
    Class c = (Class) map.valAt(sym);
    while((c == null) || (areDifferentInstancesOfSameClassName(c, val)))
        {
        IPersistentMap newMap = map.assoc(sym, val);
        mappings.compareAndSet(map, newMap);
        map = getMappings();
        c = (Class) map.valAt(sym);
        }
    if(c == val)
        return c;

    throw new IllegalStateException(sym + " already refers to: " + c + " in namespace: " + name);
}

/**
 * 干掉一个映射
 * @param sym
 */
public void unmap(Symbol sym) {
	if(sym.ns != null)
		{
		throw new IllegalArgumentException("Can't unintern namespace-qualified symbol");
		}
	IPersistentMap map = getMappings();
	// for循环，直到干掉这个映射
	while(map.containsKey(sym))
		{
		IPersistentMap newMap = map.without(sym);
		mappings.compareAndSet(map, newMap);
		map = getMappings();
		}
}

/**
 * import一个类
 * @param sym 类名的symbol
 * @param c
 * @return
 */
public Class importClass(Symbol sym, Class c){
	return referenceClass(sym, c);

}

/**
 * import一个class
 * @param c
 * @return
 */
public Class importClass(Class c){
	String n = c.getName();
	return importClass(Symbol.intern(n.substring(n.lastIndexOf('.') + 1)), c);
}

/**
 * refer其实就是把传入的sym关联到var
 * @param sym
 * @param var
 * @return
 */
public Var refer(Symbol sym, Var var){
	return (Var) reference(sym, var);

}

/**
 * find or create一个名字空间出来
 * @param name
 * @return
 */
public static Namespace findOrCreate(Symbol name){
	Namespace ns = namespaces.get(name);
	if(ns != null)
		return ns;
	Namespace newns = new Namespace(name);
	ns = namespaces.putIfAbsent(name, newns);
	return ns == null ? newns : ns;
}

/**
 * 干掉一个名字空间
 * @param name
 * @return
 */
public static Namespace remove(Symbol name){
	if(name.equals(RT.CLOJURE_NS.name))
		throw new IllegalArgumentException("Cannot remove clojure namespace");
	return namespaces.remove(name);
}

/**
 * 根据名字查出对应的名字空间对象
 * @param name
 * @return
 */
public static Namespace find(Symbol name){
	return namespaces.get(name);
}

/**
 * 获取一个名字所对应的mapping
 * @param name
 * @return
 */
public Object getMapping(Symbol name){
	return mappings.get().valAt(name);
}

/**
 * 找出一个被intern的var
 * @param symbol
 * @return
 */
public Var findInternedVar(Symbol symbol){
	Object o = mappings.get().valAt(symbol);
	if(o != null && o instanceof Var && ((Var) o).ns == this)
		return (Var) o;
	return null;
}

/**
 * 获取当前名字空间的所有的alias
 * @return
 */
public IPersistentMap getAliases(){
	return aliases.get();
}

/**
 * 查出一个alias所对应的名字空间
 * @param alias
 * @return
 */
public Namespace lookupAlias(Symbol alias){
	IPersistentMap map = getAliases();
	return (Namespace) map.valAt(alias);
}

/**
 * 添加一个alias
 * @param alias
 * @param ns
 */
public void addAlias(Symbol alias, Namespace ns){
	if (alias == null || ns == null)
		throw new NullPointerException("Expecting Symbol + Namespace");
	IPersistentMap map = getAliases();
	while(!map.containsKey(alias))
		{
		IPersistentMap newMap = map.assoc(alias, ns);
		aliases.compareAndSet(map, newMap);
		map = getAliases();
		}
	// you can rebind an alias, but only to the initially-aliased namespace.
	if(!map.valAt(alias).equals(ns))
		throw new IllegalStateException("Alias " + alias + " already exists in namespace "
		                                   + name + ", aliasing " + map.valAt(alias));
}

/**
 * 干掉一个alias
 * @param alias
 */
public void removeAlias(Symbol alias) {
	IPersistentMap map = getAliases();
	while(map.containsKey(alias))
		{
		IPersistentMap newMap = map.without(alias);
		aliases.compareAndSet(map, newMap);
		map = getAliases();
		}
}

/**
 * 把当前名字空间find or create出来
 * @return
 * @throws ObjectStreamException
 */
private Object readResolve() throws ObjectStreamException {
    // ensures that serialized namespaces are "deserialized" to the
    // namespace in the present runtime
    return findOrCreate(name);
}
}
