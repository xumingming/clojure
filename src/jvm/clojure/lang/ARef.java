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

import java.util.Map;

/**
 * ARef是所有引用类型(Ref, Agent, Atom, Var)的抽象基类
 */
public abstract class ARef extends AReference implements IRef{
/**
 * 校验函数
 */
protected volatile IFn validator = null;
/**
 * 所有的监视器
 */
private volatile IPersistentMap watches = PersistentHashMap.EMPTY;

public ARef(){
	super();
}

public ARef(IPersistentMap meta){
	super(meta);
}

/**
 * 验证一下新值是否符合要求
 * @param vf
 * @param val
 */
void validate(IFn vf, Object val){
	try
		{
		if(vf != null && !RT.booleanCast(vf.invoke(val)))
			throw new IllegalStateException("Invalid reference state");
		}
	catch(RuntimeException re)
		{
		throw re;
		}
	catch(Exception e)
		{
		throw new IllegalStateException("Invalid reference state", e);
		}
}

/**
 * 验证一下新值是否符合要求
 * @param val
 */
void validate(Object val){
	validate(validator, val);
}

/**
 * 设置validator，设置的时候就会把当前值验证一下
 */
public void setValidator(IFn vf){
	try
		{
		validate(vf, deref());
		}
	catch(Exception e)
		{
		throw Util.sneakyThrow(e);
		}
	validator = vf;
}

/**
 * 获取校验函数
 */
public IFn getValidator(){
	return validator;
}

/**
 * 获取所有的监视器
 */
public IPersistentMap getWatches(){
	return watches;
}

/**
 * 添加校验器
 */
synchronized public IRef addWatch(Object key, IFn callback){
	watches = watches.assoc(key, callback);
	return this;
}

/**
 * 删除一个校验器
 */
synchronized public IRef removeWatch(Object key){
	try
		{
		watches = watches.without(key);
		}
	catch(Exception e)
		{
		throw Util.sneakyThrow(e);
		}

	return this;
}

/**
 * 通知所有校验器状态发生了变化
 * @param oldval
 * @param newval
 */
public void notifyWatches(Object oldval, Object newval){
	IPersistentMap ws = watches;
	if(ws.count() > 0)
		{
		for(ISeq s = ws.seq(); s != null; s = s.next())
			{
			Map.Entry e = (Map.Entry) s.first();
			IFn fn = (IFn) e.getValue();
			try
				{
				if(fn != null)
					fn.invoke(e.getKey(), this, oldval, newval);
				}
			catch(Exception e1)
				{
				throw Util.sneakyThrow(e1);
				}
			}
		}
}
}
