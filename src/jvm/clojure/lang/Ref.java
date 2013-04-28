/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 25, 2007 */

package clojure.lang;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
/**
 * Ref
 * 
 * <ul>
 *  <li>每个ref都有一个id，ref之间比较的时候比较的就是这个id</li>
 * </ul>
 */
public class Ref extends ARef implements IFn, Comparable<Ref>, IRef{
    public int compareTo(Ref ref) {
        if(this.id == ref.id)
            return 0;
        else if(this.id < ref.id)
            return -1;
        else
            return 1;
    }

/**
 * 获取最小历史
 * @return
 */
public int getMinHistory(){
	return minHistory;
}

/**
 * 设置最小历史
 * @param minHistory
 * @return
 */
public Ref setMinHistory(int minHistory){
	this.minHistory = minHistory;
	return this;
}

/**
 * 获取最大历史
 * @return
 */
public int getMaxHistory(){
	return maxHistory;
}

/**
 *  设置最大历史
 * @param maxHistory
 * @return
 */
public Ref setMaxHistory(int maxHistory){
	this.maxHistory = maxHistory;
	return this;
}

public static class TVal{
    /**
     * Ref的实际值
     */
	Object val;
	/**
	 * 记录这个TVal产生的“时间”点，会用来在transaction里面做版本比较的
	 */
	long point;
	/**
	 * 该TVal生成的时间戳
	 */
	long msecs;
	/**
	 * 前一条历史记录
	 */
	TVal prior;
	/**
	 * 下一条历史
	 */
	TVal next;

	TVal(Object val, long point, long msecs, TVal prior){
		this.val = val;
		this.point = point;
		this.msecs = msecs;
		this.prior = prior;
		this.next = prior.next;
		this.prior.next = this;
		this.next.prior = this;
	}

	TVal(Object val, long point, long msecs){
		this.val = val;
		this.point = point;
		this.msecs = msecs;
		this.next = this;
		this.prior = this;
	}

}
/**
 * Ref的当前值
 */
TVal tvals;
/**
 * 操作的出错次数
 */
final AtomicInteger faults;
/**
 * 控制对ref操作的锁
 */
final ReentrantReadWriteLock lock;
/**
 * transaction信息
 */
LockingTransaction.Info tinfo;
//IFn validator;
/**
 * ref的唯一id
 */
final long id;
/**
 * 最小历史长度
 */
volatile int minHistory = 0;
/**
 * 最大历史长度
 */
volatile int maxHistory = 10;

static final AtomicLong ids = new AtomicLong();

public Ref(Object initVal) {
	this(initVal, null);
}

public Ref(Object initVal,IPersistentMap meta) {
    super(meta);
    this.id = ids.getAndIncrement();
	this.faults = new AtomicInteger();
	this.lock = new ReentrantReadWriteLock();
	tvals = new TVal(initVal, 0, System.currentTimeMillis());
}

//the latest val

// ok out of transaction
Object currentVal(){
	try
		{
	    // 加读锁
		lock.readLock().lock();
		if(tvals != null)
			return tvals.val;
		throw new IllegalStateException(this.toString() + " is unbound.");
		}
	finally
		{
	    // 解锁
		lock.readLock().unlock();
		}
}

//*
/**
 * deref, 获取ref的值，如果当前有运行中的事务，那么获取的是这个ref在事务内的值；否则返回当前值 -- 也就是最后提交的值
 */
public Object deref(){
    // 获取正在运行的transaction
	LockingTransaction t = LockingTransaction.getRunning();
	// 如果没有运行中的transaction，那么直接返回当前值
	if(t == null)
		return currentVal();
	return t.doGet(this);
}

//void validate(IFn vf, Object val){
//	try{
//		if(vf != null && !RT.booleanCast(vf.invoke(val)))
//            throw new IllegalStateException("Invalid ref state");
//		}
//    catch(RuntimeException re)
//        {
//        throw re;
//        }
//	catch(Exception e)
//		{
//		throw new IllegalStateException("Invalid ref state", e);
//		}
//}
//
//public void setValidator(IFn vf){
//	try
//		{
//		lock.writeLock().lock();
//		validate(vf,currentVal());
//		validator = vf;
//		}
//	finally
//		{
//		lock.writeLock().unlock();
//		}
//}
//
//public IFn getValidator(){
//	try
//		{
//		lock.readLock().lock();
//		return validator;
//		}
//	finally
//		{
//		lock.readLock().unlock();
//		}
//}

/**
 * 设置ref的值: (ref-set ref val)
 * @param val
 * @return
 */
public Object set(Object val){
	return LockingTransaction.getEx().doSet(this, val);
}

/**
 * (commute ref fn args)
 * 
 * commute这个函数比较有意思，它是直接把ref设成: (fn ref args)并且返回，但是最终ref到底是什么值呢？
 * 在事务提交的时候还会再调用(fn ref args)一遍，这次的ref基于的是ref的最新值
 * @param fn
 * @param args
 * @return
 */
public Object commute(IFn fn, ISeq args) {
	return LockingTransaction.getEx().doCommute(this, fn, args);
}

/**
 * alter其实就是ref-set只不过提供的参数不是最终的newv，而是fn + args
 * @param fn
 * @param args
 * @return
 */
public Object alter(IFn fn, ISeq args) {
	LockingTransaction t = LockingTransaction.getEx();
	return t.doSet(this, fn.applyTo(RT.cons(t.doGet(this), args)));
}

/**
 * touch == ensure
 */
public void touch(){
	LockingTransaction.getEx().doEnsure(this);
}

//*/
/**
 * 当前ref有bound吗？
 * @return
 */
boolean isBound(){
	try
		{
		lock.readLock().lock();
		return tvals != null;
		}
	finally
		{
		lock.readLock().unlock();
		}
}

/**
 * 把历史信息都干掉：prior == this next == this
 */
public void trimHistory(){
	try
		{
		lock.writeLock().lock();
		if(tvals != null)
			{
			tvals.next = tvals;
			tvals.prior = tvals;
			}
		}
	finally
		{
		lock.writeLock().unlock();
		}
}

public int getHistoryCount(){
	try
		{
		lock.writeLock().lock();
		return histCount();
		}
	finally
		{
		lock.writeLock().unlock();
		}	
}

int histCount(){
	if(tvals == null)
		return 0;
	else
		{
		int count = 0;
		// 遍历整个tvals链条，来看看一共有多少历史记录
		for(TVal tv = tvals.next;tv != tvals;tv = tv.next)
			count++;
		return count;
		}
}
/**
 * deref
 * @return
 */
final public IFn fn(){
	return (IFn) deref();
}

/**
 * deref
 */
public Object call() {
	return invoke();
}

/**
 * deref
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

/**
 * deref
 */
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

}
