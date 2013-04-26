/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jun 28, 2007 */

package clojure.lang;

public class Delay implements IDeref, IPending{
Object val;
IFn fn;

public Delay(IFn fn){
	this.fn = fn;
	this.val = null;
}

/**
 * 静态方法，如果x是Delay，那么调用它的deref,否则直接返回
 * @param x
 * @return
 */
static public Object force(Object x) {
	return (x instanceof Delay) ?
	       ((Delay) x).deref()
	       : x;
}

/**
 * 如果还没实例化，那么调用fn来进行计算，执行完毕把fn值为null
 */
synchronized public Object deref() {
	if(fn != null)
		{
		val = fn.invoke();
		fn = null;
		}
	return val;
}

synchronized public boolean isRealized(){
    // 当被实例化之后fn会被置为null
	return fn == null;
}
}
