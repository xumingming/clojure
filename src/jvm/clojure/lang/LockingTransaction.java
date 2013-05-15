/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 26, 2007 */

package clojure.lang;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

@SuppressWarnings({"SynchronizeOnNonFinalField"})
public class LockingTransaction{
/**
 * 事务的最大重试次数
 */
public static final int RETRY_LIMIT = 10000;
/**
 * 获取锁的最大等待时间100ms
 */
public static final int LOCK_WAIT_MSECS = 100;
/**
 * 干预的最大等待时间
 */
public static final long BARGE_WAIT_NANOS = 10 * 1000000;
//public static int COMMUTE_RETRY_LIMIT = 10;

static final int RUNNING = 0;
static final int COMMITTING = 1;
static final int RETRY = 2;
static final int KILLED = 3;
static final int COMMITTED = 4;
/**
 * 当前线程的事务
 */
final static ThreadLocal<LockingTransaction> transaction = new ThreadLocal<LockingTransaction>();


static class RetryEx extends Error{
}

static class AbortException extends Exception{
}

/**
 * transaction信息 
 */
public static class Info{
    /**
     * 事务的状态
     */
	final AtomicInteger status;
	final long startPoint;
	final CountDownLatch latch;


	public Info(int status, long startPoint){
		this.status = new AtomicInteger(status);
		this.startPoint = startPoint;
		this.latch = new CountDownLatch(1);
	}

	/**
	 * 状态是RUNNING或者COMMITING
	 * @return
	 */
	public boolean running(){
		int s = status.get();
		return s == RUNNING || s == COMMITTING;
	}
}

static class CFn{
	final IFn fn;
	final ISeq args;

	public CFn(IFn fn, ISeq args){
		this.fn = fn;
		this.args = args;
	}
}
//total order on transactions
//transactions will consume a point for init, for each retry, and on commit if writing
/**
 * point相当于版本、打点，每个transaction在init，重试，提交的时候这个point都是升高。
 */
final private static AtomicLong lastPoint = new AtomicLong();

void getReadPoint(){
	readPoint = lastPoint.incrementAndGet();
}

long getCommitPoint(){
	return lastPoint.incrementAndGet();
}

/**
 * 
 * @param status
 */
void stop(int status){
	if(info != null)
		{
		synchronized(info)
			{
			info.status.set(status);
			info.latch.countDown();
			}
		info = null;
		vals.clear();
		sets.clear();
		commutes.clear();
		//actions.clear();
		}
}


Info info;
/**
 * readPoint的值在每次事务开始的时候变更
 */
long readPoint;
/**
 * 事务开始的point
 */
long startPoint;
long startTime;
final RetryEx retryex = new RetryEx();
final ArrayList<Agent.Action> actions = new ArrayList<Agent.Action>();
/**
 * 所有ref以及它们所对应的值
 */
final HashMap<Ref, Object> vals = new HashMap<Ref, Object>();
/**
 * 被调用了ref-set的ref集合
 */
final HashSet<Ref> sets = new HashSet<Ref>();
/**
 * 一个commit其实就是一个函数调用，每个ref可以对应多个commute
 */
final TreeMap<Ref, ArrayList<CFn>> commutes = new TreeMap<Ref, ArrayList<CFn>>();

/**
 * 所有上了读锁的ref
 */
final HashSet<Ref> ensures = new HashSet<Ref>();   //all hold readLock

/**
 * 尝试获取ref上的写锁
 * @param ref
 */
void tryWriteLock(Ref ref){
	try
		{
		if(!ref.lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS)) 
		    {
		    System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#tryWriteLock: retry because tryWriteLock timeout");
			throw retryex;
		    }
		}
	catch(InterruptedException e)
		{
		throw retryex;
		}
}

//returns the most recent val
/**
 * 给ref加写锁以获取ref的最新值. 加写锁之后还会看看是不是在当前事务开始之后有别的事务提交过。
 * @param ref
 * @return
 */
Object lock(Ref ref){
	//can't upgrade readLock, so release it
	releaseIfEnsured(ref);

	boolean unlocked = true;
	try
		{
		tryWriteLock(ref);
		unlocked = false;

		if(ref.tvals != null && ref.tvals.point > readPoint) {
		    System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#lock: retry because ref modified after this tx begins");
            
			throw retryex;
		}
		Info refinfo = ref.tinfo;

		//write lock conflict
		if(refinfo != null && refinfo != info && refinfo.running())
			{
			if(!barge(refinfo))
				{
				ref.lock.writeLock().unlock();
				unlocked = true;
				return blockAndBail(refinfo);
				}
			}
		ref.tinfo = info;
		return ref.tvals == null ? null : ref.tvals.val;
		}
	finally
		{
		if(!unlocked)
			ref.lock.writeLock().unlock();
		}
}

/**
 * stop & throw retryex
 * @param refinfo
 * @return
 */
private Object blockAndBail(Info refinfo){
//stop prior to blocking
	stop(RETRY);
	try
		{
		refinfo.latch.await(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS);
		}
	catch(InterruptedException e)
		{
		//ignore
		}
	throw retryex;
}

/**
 * 干掉ref对应的ensure，并unlockref上的读锁
 * @param ref
 */
private void releaseIfEnsured(Ref ref){
	if(ensures.contains(ref))
		{
		ensures.remove(ref);
		ref.lock.readLock().unlock();
		}
}

void abort() throws AbortException{
	stop(KILLED);
	throw new AbortException();
}

/**
 * barge有没有超时
 * @return
 */
private boolean bargeTimeElapsed(){
	return System.nanoTime() - startTime > BARGE_WAIT_NANOS;
}

/**
 * 人工干预？把别的事务的refinfo跟当前事务比，如果当前事务开始的比较早，那么把别的事务干掉，latch countdown。
 * @param refinfo
 * @return
 */
private boolean barge(Info refinfo){
	boolean barged = false;
	//if this transaction is older
	//  try to abort the other
	if(bargeTimeElapsed() && startPoint < refinfo.startPoint)
		{
        barged = refinfo.status.compareAndSet(RUNNING, KILLED);
        if(barged)
            refinfo.latch.countDown();
		}
	return barged;
}

/**
 * 获取当前正在运行的transaction
 * @return
 */
static LockingTransaction getEx(){
	LockingTransaction t = transaction.get();
	if(t == null || t.info == null)
		throw new IllegalStateException("No transaction running");
	return t;
}

/**
 * 当前是否有transaction在运行？
 * @return
 */
static public boolean isRunning(){
	return getRunning() != null;
}

/**
 * 获取当前正在运行的transaction
 * @return
 */
static LockingTransaction getRunning(){
	LockingTransaction t = transaction.get();
	if(t == null || t.info == null)
		return null;
	return t;
}

/**
 * 在一个transaction内调用一个函数
 * @param fn
 * @return
 * @throws Exception
 */
static public Object runInTransaction(Callable fn) throws Exception{
	LockingTransaction t = transaction.get();
	if(t == null)
		transaction.set(t = new LockingTransaction());

	if(t.info != null)
		return fn.call();

	return t.run(fn);
}

/**
 * notify信息，包含发生变化的ref，它的新值和旧值
 */
static class Notify{
	final public Ref ref;
	final public Object oldval;
	final public Object newval;

	Notify(Ref ref, Object oldval, Object newval){
		this.ref = ref;
		this.oldval = oldval;
		this.newval = newval;
	}
}

Object run(Callable fn) throws Exception{
	boolean done = false;
	Object ret = null;
	/**
	 * 所有上了写锁的ref
	 */
	ArrayList<Ref> locked = new ArrayList<Ref>();
	/**
	 * 所有要发的notify消息
	 */
	ArrayList<Notify> notify = new ArrayList<Notify>();

	for(int i = 0; !done && i < RETRY_LIMIT; i++)
		{
	    System.out.println("[tx:" + this.hashCode() + "] round:" + i);
		try
			{
			getReadPoint();
			if(i == 0)
				{
				startPoint = readPoint;
				startTime = System.nanoTime();
				}
			info = new Info(RUNNING, startPoint);
			ret = fn.call();
			//make sure no one has killed us before this point, and can't from now on
			// 这里先compareset一下，确保没有别的线程已经把我们干掉了(改了status状态), 一旦状态改成COMMITING之后别人就干不了我们了
			if(info.status.compareAndSet(RUNNING, COMMITTING))
				{
				for(Map.Entry<Ref, ArrayList<CFn>> e : commutes.entrySet())
					{
					Ref ref = e.getKey();
					// 对于要set的，直接跳过
					if(sets.contains(ref)) continue;
					
					boolean wasEnsured = ensures.contains(ref);
					//can't upgrade readLock, so release it
					// 释放读锁
					releaseIfEnsured(ref);
					// 上写锁
					try {
					    tryWriteLock(ref);
					} catch (RetryEx e1) {
					    System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#run: retry because cannt get write lock");
					    throw e1;
					}
					locked.add(ref);
					
					// 对于ensure的ref，但是在snapshot之后又有人写了，那么重试
					if(wasEnsured && ref.tvals != null && ref. tvals.point > readPoint) {
					    System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#run: retry because ref is ensured but modified by other tx");
						throw retryex;
					}

					Info refinfo = ref.tinfo;
					// 如果有别的线程的事务里面在改这个ref，那么协调一下：
					// refinfo != null 意味着别的事务对这个ref调用了ref-set或者alter
					//   1) 如果别的事务比当前事务年轻，那么干掉它。
					//   2) 否则当前事务重试
					// 这里是为了确保没有别的事务在操作这个ref
					if(refinfo != null && refinfo != info && refinfo.running())
						{
						if(!barge(refinfo))
						    System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#run: retry because other tx are modifing the ref");
							throw retryex;
						}
					Object val = ref.tvals == null ? null : ref.tvals.val;
					// 把这个ref的值put进去，好后面取出来操作
					vals.put(ref, val);
					
					// 执行到这里我们可以确保没有人在修改这个ref，那么
					// 依次调用所有的commute函数来修改这个ref的值
					for(CFn f : e.getValue())
						{
						vals.put(ref, f.fn.applyTo(RT.cons(vals.get(ref), f.args)));
						}
					}
				
				
				// 对于所有的sets里面的ref，上写锁，并加入locked
				for(Ref ref : sets)
					{
                    try {
                        tryWriteLock(ref);
                    } catch (RetryEx e1) {
                        System.out.println("[tx: " + this.hashCode() + "] LockingTransaction#run: retry because cannt get write lock");
                        throw e1;
                    }
					locked.add(ref);
					}

				//validate and enqueue notifications
				// 到这里为止，ref的值都写完了，校验一下ref的新值
				for(Map.Entry<Ref, Object> e : vals.entrySet())
					{
					Ref ref = e.getKey();
					ref.validate(ref.getValidator(), e.getValue());
					}

				// 到这里为止，所有的值都算出来了，所有的ref的写锁都上了。
				// 已经没有什么客户端代码需要调用了。
				//at this point, all values calced, all refs to be written locked
				//no more client code to be called
				long msecs = System.currentTimeMillis();
				long commitPoint = getCommitPoint();
				for(Map.Entry<Ref, Object> e : vals.entrySet())
					{
					Ref ref = e.getKey();
					Object oldval = ref.tvals == null ? null : ref.tvals.val;
					Object newval = e.getValue();
					int hcount = ref.histCount();

					if(ref.tvals == null)
						{
						ref.tvals = new Ref.TVal(newval, commitPoint, msecs);
						}
					else if((ref.faults.get() > 0 && hcount < ref.maxHistory)
							|| hcount < ref.minHistory)
						{
						ref.tvals = new Ref.TVal(newval, commitPoint, msecs, ref.tvals);
						ref.faults.set(0);
						}
					else
						{
						ref.tvals = ref.tvals.next;
						ref.tvals.val = newval;
						ref.tvals.point = commitPoint;
						ref.tvals.msecs = msecs;
						}
					// 如果有监视器，那么准备发送notify消息
					if(ref.getWatches().count() > 0)
						notify.add(new Notify(ref, oldval, newval));
					}

				done = true;
				info.status.set(COMMITTED);
				}
			}
		catch(RetryEx retry)
			{
			//eat this so we retry rather than fall out
			}
		finally
			{
		    // 开所有的写锁
			for(int k = locked.size() - 1; k >= 0; --k)
				{
				locked.get(k).lock.writeLock().unlock();
				}
			locked.clear();
			// 开所有的读锁
			for(Ref r : ensures)
				{
				r.lock.readLock().unlock();
				}
			ensures.clear();
			stop(done ? COMMITTED : RETRY);
			try
				{
				if(done) //re-dispatch out of transaction
					{
				    // 发送notify消息
					for(Notify n : notify)
						{
						n.ref.notifyWatches(n.oldval, n.newval);
						}
					// dispatch所有的action
					for(Agent.Action action : actions)
						{
						Agent.dispatchAction(action);
						}
					}
				}
			finally
				{
				notify.clear();
				actions.clear();
				}
			}
		}
	if(!done)
		throw Util.runtimeException("Transaction failed after reaching retry limit");
	return ret;
}

/**
 * 添加一个新的action
 * @param action
 */
public void enqueue(Agent.Action action){
	actions.add(action);
}

/**
 * 获取ref的值
 * 
 * <ul>
 *  <li>如果当前事务不在运行，那么抛出重试错误</li>
 *  <li>如果vals有ref的值，那么直接取出返回</li>
 *  <li>否则加一个读锁</li>
 *  <li>如果ref里面的值是null，那么抛出异常</li>
 *  <li>遍历ref的值历史链条，直到找到一个值的point比当前事务的readPoint要小，那么返回这个值. 也就是说获取的值是事务内的最新值</li>
 *  <li>解除读锁</li>
 *  <li>程序如果执行到这里而没有返回说明没有找到值，那么ref.faults加一，抛出重试错误</li>
 * </ul>
 * @param ref
 * @return
 */
Object doGet(Ref ref){
	if(!info.running())
		throw retryex;
	if(vals.containsKey(ref))
		return vals.get(ref);
	try
		{
		ref.lock.readLock().lock();
		if(ref.tvals == null)
			throw new IllegalStateException(ref.toString() + " is unbound.");
		Ref.TVal ver = ref.tvals;
		do
			{
			if(ver.point <= readPoint)
				return ver.val;
			} while((ver = ver.prior) != ref.tvals);
		}
	finally
		{
		ref.lock.readLock().unlock();
		}
	//no version of val precedes the read point
	ref.faults.incrementAndGet();
	throw retryex;

}
/**
 * 给一个ref设值 ref-set
 * 
 * 如果sets里面没有ref会把ref加到sets里面
 * 把ref val放到vals里面
 * @param ref
 * @param val
 * @return
 */
Object doSet(Ref ref, Object val){
	if(!info.running())
		throw retryex;
	// commute之后就不能调用set了
	if(commutes.containsKey(ref))
		throw new IllegalStateException("Can't set after commute");
	if(!sets.contains(ref))
		{
		sets.add(ref);
		try {
		lock(ref);
		} catch (RetryEx e) {
		    System.out.println("[tx:" + this.hashCode() + "] LockingTransaction#doSet: retry because cannt get write lock");
		    throw e;
		}
		}
	vals.put(ref, val);
	return val;
}

/**
 * ensure == (ref-set ref @ref) 但是并发性能更好。它会上一个读锁
 * 
 * @param ref
 */
void doEnsure(Ref ref){
	if(!info.running())
		throw retryex;
	if(ensures.contains(ref))
		return;
	ref.lock.readLock().lock();

	//someone completed a write after our snapshot
	// 如果在我们事务开始之后有人写过这个ref，那么我们释放读锁，并且抛出重试异常
	if(ref.tvals != null && ref.tvals.point > readPoint) {
        ref.lock.readLock().unlock();
        System.out.println("[tx:" + this.hashCode() + "] LockingTransaction#doEnsure: retry because ref modified after this tx begins");
        throw retryex;
    }

	Info refinfo = ref.tinfo;

	//writer exists
	// 如果有别的transaction正在对这个ref进行操作，那么我们释放读锁并且停止当前transaction
	if(refinfo != null && refinfo.running())
		{
		ref.lock.readLock().unlock();

		// 有其他线程在改这个ref，那么停止当前事务并重试
		if(refinfo != info) //not us, ensure is doomed
			{
			blockAndBail(refinfo); 
			}
		}
	else
		ensures.add(ref);
}

/**
 * 如果vals里面没有ref的值，那么先把ref的值加入vals
 * @param ref
 * @param fn
 * @param args
 * @return
 */
Object doCommute(Ref ref, IFn fn, ISeq args) {
	if(!info.running())
		throw retryex;
	if(!vals.containsKey(ref))
		{
		Object val = null;
		try
			{
			ref.lock.readLock().lock();
			val = ref.tvals == null ? null : ref.tvals.val;
			}
		finally
			{
			ref.lock.readLock().unlock();
			}
		vals.put(ref, val);
		}
	ArrayList<CFn> fns = commutes.get(ref);
	if(fns == null)
		commutes.put(ref, fns = new ArrayList<CFn>());
	// 这里把要调用的函数跟参数保存起来了
	fns.add(new CFn(fn, args));
	// 这里怎么又直接调用fn了
	Object ret = fn.applyTo(RT.cons(vals.get(ref), args));
	vals.put(ref, ret);
	return ret;
}

/*
//for test
static CyclicBarrier barrier;
static ArrayList<Ref> items;

public static void main(String[] args){
	try
		{
		if(args.length != 4)
			System.err.println("Usage: LockingTransaction nthreads nitems niters ninstances");
		int nthreads = Integer.parseInt(args[0]);
		int nitems = Integer.parseInt(args[1]);
		int niters = Integer.parseInt(args[2]);
		int ninstances = Integer.parseInt(args[3]);

		if(items == null)
			{
			ArrayList<Ref> temp = new ArrayList(nitems);
			for(int i = 0; i < nitems; i++)
				temp.add(new Ref(0));
			items = temp;
			}

		class Incr extends AFn{
			public Object invoke(Object arg1) {
				Integer i = (Integer) arg1;
				return i + 1;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		class Commuter extends AFn implements Callable{
			int niters;
			List<Ref> items;
			Incr incr;


			public Commuter(int niters, List<Ref> items){
				this.niters = niters;
				this.items = items;
				this.incr = new Incr();
			}

			public Object call() {
				long nanos = 0;
				for(int i = 0; i < niters; i++)
					{
					long start = System.nanoTime();
					LockingTransaction.runInTransaction(this);
					nanos += System.nanoTime() - start;
					}
				return nanos;
			}

			public Object invoke() {
				for(Ref tref : items)
					{
					LockingTransaction.getEx().doCommute(tref, incr);
					}
				return null;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		class Incrementer extends AFn implements Callable{
			int niters;
			List<Ref> items;


			public Incrementer(int niters, List<Ref> items){
				this.niters = niters;
				this.items = items;
			}

			public Object call() {
				long nanos = 0;
				for(int i = 0; i < niters; i++)
					{
					long start = System.nanoTime();
					LockingTransaction.runInTransaction(this);
					nanos += System.nanoTime() - start;
					}
				return nanos;
			}

			public Object invoke() {
				for(Ref tref : items)
					{
					//Transaction.get().doTouch(tref);
//					LockingTransaction t = LockingTransaction.getEx();
//					int val = (Integer) t.doGet(tref);
//					t.doSet(tref, val + 1);
					int val = (Integer) tref.get();
					tref.set(val + 1);
					}
				return null;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		ArrayList<Callable<Long>> tasks = new ArrayList(nthreads);
		for(int i = 0; i < nthreads; i++)
			{
			ArrayList<Ref> si;
			synchronized(items)
				{
				si = (ArrayList<Ref>) items.clone();
				}
			Collections.shuffle(si);
			tasks.add(new Incrementer(niters, si));
			//tasks.add(new Commuter(niters, si));
			}
		ExecutorService e = Executors.newFixedThreadPool(nthreads);

		if(barrier == null)
			barrier = new CyclicBarrier(ninstances);
		System.out.println("waiting for other instances...");
		barrier.await();
		System.out.println("starting");
		long start = System.nanoTime();
		List<Future<Long>> results = e.invokeAll(tasks);
		long estimatedTime = System.nanoTime() - start;
		System.out.printf("nthreads: %d, nitems: %d, niters: %d, time: %d%n", nthreads, nitems, niters,
		                  estimatedTime / 1000000);
		e.shutdown();
		for(Future<Long> result : results)
			{
			System.out.printf("%d, ", result.get() / 1000000);
			}
		System.out.println();
		System.out.println("waiting for other instances...");
		barrier.await();
		synchronized(items)
			{
			for(Ref item : items)
				{
				System.out.printf("%d, ", (Integer) item.currentVal());
				}
			}
		System.out.println("\ndone");
		System.out.flush();
		}
	catch(Exception ex)
		{
		ex.printStackTrace();
		}
}
*/
}
