/**
 * Kernel — AdaiOS 核心层。
 * <p>
 * Kernel 是操作系统的内核，不是业务服务。
 * 所有系统级能力（Identity、Record、Timeline、Context、Memory、Knowledge）
 * 在此层定义，被所有 Domain OS 共享。
 * <p>
 * Kernel 内的组件之间有一定的调用关系（如 Record → Timeline → Context → Memory），
 * 但 Kernel 整体对外是稳定的内部总线。
 */
package com.adaiadai.core.kernel;
