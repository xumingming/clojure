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
 * ARef��������������(Ref, Agent, Atom, Var)�ĳ������
 */
public abstract class ARef extends AReference implements IRef{
/**
 * У�麯��
 */
protected volatile IFn validator = null;
/**
 * ���еļ�����
 */
private volatile IPersistentMap watches = PersistentHashMap.EMPTY;

public ARef(){
	super();
}

public ARef(IPersistentMap meta){
	super(meta);
}

/**
 * ��֤һ����ֵ�Ƿ����Ҫ��
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
 * ��֤һ����ֵ�Ƿ����Ҫ��
 * @param val
 */
void validate(Object val){
	validate(validator, val);
}

/**
 * ����validator�����õ�ʱ��ͻ�ѵ�ǰֵ��֤һ��
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
 * ��ȡУ�麯��
 */
public IFn getValidator(){
	return validator;
}

/**
 * ��ȡ���еļ�����
 */
public IPersistentMap getWatches(){
	return watches;
}

/**
 * ���У����
 */
synchronized public IRef addWatch(Object key, IFn callback){
	watches = watches.assoc(key, callback);
	return this;
}

/**
 * ɾ��һ��У����
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
 * ֪ͨ����У����״̬�����˱仯
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
