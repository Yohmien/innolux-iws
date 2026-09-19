/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.core.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * 线程工具
 *
 * @author YONGJIE.PENG
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ThreadUtils {

    /**
     * 批量执行任务
     */
    public static void virtualInvokeAll(Runnable... runnableList) {
        List<Future<?>> callableList = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable runnable : runnableList) {
                callableList.add(executor.submit(runnable));
            }
            for (Future<?> future : callableList) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("线程执行异常：" + cause.getMessage(), cause);
        }

    }

    /**
     * 批量执行有返回值的任务
     *
     * @param supplierList 任务列表
     * @param <T>          返回值类型
     * @return 按提交顺序返回的任务结果
     */
    @SafeVarargs
    public static <T> List<T> virtualSubmitAll(Supplier<T>... supplierList) {
        return virtualSubmitAll(List.of(supplierList));
    }

    /**
     * 批量执行有返回值的任务
     *
     * @param supplierList 任务列表
     * @param <T>          返回值类型
     * @return 按提交顺序返回的任务结果
     */
    public static <T> List<T> virtualSubmitAll(Collection<? extends Supplier<T>> supplierList) {
        List<Future<T>> futureList = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Supplier<T> supplier : supplierList) {
                futureList.add(executor.submit(supplier::get));
            }
            List<T> resultList = new ArrayList<>(futureList.size());
            for (Future<T> future : futureList) {
                resultList.add(future.get());
            }
            return resultList;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("线程执行异常：" + cause.getMessage(), cause);
        }
    }

}
