/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Nov 18, 2007 */

package clojure.lang;

public interface IRef extends IDeref{

    /**
     * 设置validator函数
     * @param vf
     */
	void setValidator(IFn vf);

	/**
	 * 获取validator
	 * @return
	 */
    IFn getValidator();

    /**
     * 查出所有的监视器
     * @return
     */
    IPersistentMap getWatches();
    
    /**
     * 添加监视器
     * @param key
     * @param callback
     * @return
     */
    IRef addWatch(Object key, IFn callback);

    /**
     * 去除一个监视器
     * @param key
     * @return
     */
    IRef removeWatch(Object key);

}
