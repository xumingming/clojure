/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jan 1, 2009 */

package clojure.lang;

import java.util.concurrent.atomic.AtomicReference;

/**
 * atom保证原子地对所有的状态改变进行执行
 * 
 */
final public class Atom extends ARef{
final AtomicReference state;

public Atom(Object state){
	this.state = new AtomicReference(state);
}

public Atom(Object state, IPersistentMap meta){
	super(meta);
	this.state = new AtomicReference(state);
}

public Object deref(){
	return state.get();
}

/**
 * 有点像ref的commute, newv = (f oldv)
 * @param f
 * @return
 */
public Object swap(IFn f) {
	for(; ;)
		{
		Object v = deref();
		Object newv = f.invoke(v);
		validate(newv);
		if(state.compareAndSet(v, newv))
			{
			notifyWatches(v, newv);
			return newv;
			}
		}
}

/**
 * 有点像ref的commute, newv = (f v arg)
 * @param f
 * @param arg
 * @return
 */
public Object swap(IFn f, Object arg) {
	for(; ;)
		{
		Object v = deref();
		Object newv = f.invoke(v, arg);
		validate(newv);
		if(state.compareAndSet(v, newv))
			{
			notifyWatches(v, newv);
			return newv;
			}
		}
}

/**
 * 有点像ref的commute, newv = (f oldv arg1 arg2)
 * @param f
 * @param arg1
 * @param arg2
 * @return
 */
public Object swap(IFn f, Object arg1, Object arg2) {
	for(; ;)
		{
		Object v = deref();
		Object newv = f.invoke(v, arg1, arg2);
		validate(newv);
		if(state.compareAndSet(v, newv))
			{
			notifyWatches(v, newv);
			return newv;
			}
		}
}

/**
 * 有点像ref的commute, newv = (f oldv x y args)
 * @param f
 * @param x
 * @param y
 * @param args
 * @return
 */
public Object swap(IFn f, Object x, Object y, ISeq args) {
	for(; ;)
		{
		Object v = deref();
		Object newv = f.applyTo(RT.listStar(v, x, y, args));
		validate(newv);
		if(state.compareAndSet(v, newv))
			{
			notifyWatches(v, newv);
			return newv;
			}
		}
}

/**
 * compareAndSet newv
 * @param oldv
 * @param newv
 * @return
 */
public boolean compareAndSet(Object oldv, Object newv){
	validate(newv);
	boolean ret = state.compareAndSet(oldv, newv);
	if(ret)
		notifyWatches(oldv, newv);
	return ret;
}

/**
 * 无条件reset
 * @param newval
 * @return
 */
public Object reset(Object newval){
	Object oldval = state.get();
	validate(newval);
	state.set(newval);
	notifyWatches(oldval, newval);
	return newval;
}
}
