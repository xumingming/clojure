/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Mar 25, 2006 11:42:47 AM */

package clojure.lang;

import java.io.Serializable;
import java.io.ObjectStreamException;

/**
 * symbol在clojure里面是其它东西的名字
 * 
 *  symbol的ns和name都被intern了。
 *  symbol实现了IFn.invoke, 因此可以被调用
 */
public class Symbol extends AFn implements IObj, Comparable, Named, Serializable{
//these must be interned strings!
/**
 * 该symbol所在名字空间的名字
 */
final String ns;
/**
 * 该symbol自己的名字
 */
final String name;
/**
 * 这个symbol的hash
 */
final int hash;
final IPersistentMap _meta;
/**
 * 它缓存该Symbol的字符串形式(toString的返回结果)， 为了避免每次toString都要重新计算
 */
String _str;

public String toString(){
	if(_str == null){
		if(ns != null)
			_str = (ns + "/" + name).intern();
		else
			_str = name;
	}
	return _str;
}

public String getNamespace(){
	return ns;
}

public String getName(){
	return name;
}

// the create thunks preserve binary compatibility with code compiled
// against earlier version of Clojure and can be removed (at some point).
static public Symbol create(String ns, String name) {
    return Symbol.intern(ns, name);
}

static public Symbol create(String nsname) {
    return Symbol.intern(nsname);
}

/**
 * symbol的intern其实是把它的ns和name这两个字符串，然后new一个Symbol返回
 * @param ns
 * @param name
 * @return
 */
static public Symbol intern(String ns, String name){
	return new Symbol(ns == null ? null : ns.intern(), name.intern());
}

/**
 * 参数nsname里面可能同时包含了ns和name，内部还是调用的两个参数那个intern
 * @param nsname
 * @return
 */
static public Symbol intern(String nsname){
	int i = nsname.lastIndexOf('/');
	if(i == -1 || nsname.equals("/"))
		return new Symbol(null, nsname.intern());
	else
		return new Symbol(nsname.substring(0, i).intern(), nsname.substring(i + 1).intern());
}

private Symbol(String ns_interned, String name_interned){
	this.name = name_interned;
	this.ns = ns_interned;
	this.hash = Util.hashCombine(name.hashCode(), Util.hash(ns));
	this._meta = null;
}

public boolean equals(Object o){
	if(this == o)
		return true;
	if(!(o instanceof Symbol))
		return false;

	Symbol symbol = (Symbol) o;

	//identity compares intended, names are interned
	return name == symbol.name && ns == symbol.ns;
}

public int hashCode(){
	return hash;
}

public IObj withMeta(IPersistentMap meta){
	return new Symbol(meta, ns, name);
}

private Symbol(IPersistentMap meta, String ns, String name){
	this.name = name;
	this.ns = ns;
	this._meta = meta;
	this.hash = Util.hashCombine(name.hashCode(), Util.hash(ns));
}

/**
 * 先比较ns，再比较name
 */
public int compareTo(Object o){
	Symbol s = (Symbol) o;
	if(this.equals(o))
		return 0;
	if(this.ns == null && s.ns != null)
		return -1;
	if(this.ns != null)
		{
		if(s.ns == null)
			return 1;
		int nsc = this.ns.compareTo(s.ns);
		if(nsc != 0)
			return nsc;
		}
	return this.name.compareTo(s.name);
}

private Object readResolve() throws ObjectStreamException{
	return intern(ns, name);
}

/**
 * 这就是symbol为什么可以调用的原因
 */
public Object invoke(Object obj) {
	return RT.get(obj, this);
}

public Object invoke(Object obj, Object notFound) {
	return RT.get(obj, this, notFound);
}

public IPersistentMap meta(){
	return _meta;
}
}
