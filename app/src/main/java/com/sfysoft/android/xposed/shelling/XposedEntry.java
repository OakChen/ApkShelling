/*
 * Copyright (c) 2019 - Oak Chen <oak@sfysoft.com>
 * 2019-07-26 Oak Chen  Created
 */

package com.sfysoft.android.xposed.shelling;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * Xposed entry to shelling app
 *
 * @author Oak Chen
 */
public class XposedEntry implements IXposedHookLoadPackage {
    private static final boolean DEBUG = false;
    /**
     * 加固应用的初始类，对应AndroidManifests.xml里的<application android:name的值
     * com.stub.StubApp 360加固
     * s.h.e.l.l.S 爱加密
     * com.secneo.apkwrapper.ApplicationWrapper 梆梆加固
     * com.SecShell.SecShell.ApplicationWrapper 梆梆加固
     * com.tencent.StubShell.TxAppEntry 腾讯乐固
     * com.baidu.protect.StubApplication 百度加固
     */
    private static final String[] PACKED_APP_ENTRIES =
            {"com.stub.StubApp", "s.h.e.l.l.S", "com.secneo.apkwrapper.ApplicationWrapper",
             "com.SecShell.SecShell.ApplicationWrapper", "com.tencent.StubShell.TxAppEntry",
             "com.baidu.protect.StubApplication"};

    /**
     * 拟脱壳的App包名，对应AndroidManifests.xml里的<manifest package的值
     */
    private static final String targetPackage = "com.sfysoft.shellingtest";

    private static void log(String text) {
        XposedBridge.log(text);
    }

    private static void log(Throwable throwable) {
        XposedBridge.log(throwable);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        log("Load package: " + lpparam.packageName);
        if (!lpparam.packageName.equals(targetPackage)) {
            return;
        }

        for (String application : PACKED_APP_ENTRIES) {
            Class cls = XposedHelpers.findClassIfExists(application, lpparam.classLoader);
            if (cls != null) {
                log("Found " + application);
                ClassLoaderHook hook;
                try {
                    hook = new ClassLoaderHook(getSavingPath(targetPackage));
                    XposedHelpers.findAndHookMethod("java.lang.ClassLoader", lpparam.classLoader,
                                                    "loadClass", String.class, boolean.class, hook);
                } catch (NoSuchMethodException | ClassNotFoundException e) {
                    log(e);
                }
                break;
            }
        }
    }

    private String getSavingPath(String packageName) {
        return "/data/data/" + packageName;
    }

    private static class ClassLoaderHook extends XC_MethodHook {
        private DexOutputTask dexOutputTask;
        private Method getDex;
        private Method getBytes;

        ClassLoaderHook(String dexSavingPath) throws ClassNotFoundException, NoSuchMethodException {
            // libcore/dex/src/main/java/com/android/dex/Dex.java(Android 5.1.1)
            getBytes = Class.forName("com.android.dex.Dex").getDeclaredMethod("getBytes");
            // libcore/libart/src/main/java/java/lang/Class.java(Android 5.1.1)
            // noinspection JavaReflectionMemberAccess
            getDex = Class.forName("java.lang.Class").getDeclaredMethod("getDex");
            dexOutputTask = new DexOutputTask(dexSavingPath);
            new Thread(dexOutputTask).start();
        }

        boolean shouldSkip(String className) {
            if (className == null) {
                return true;
            }
            if (className.startsWith("java")) {
                return true;
            }
            if (className.startsWith("android")) {
                return true;
            }

            return false;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Class cls = (Class) param.getResult();
            if (cls == null) {
                return;
            }

            if (shouldSkip(cls.getName())) {
                return;
            }

            Object dex;
            byte[] bytes = null;
            try {
                dex = getDex.invoke(cls);
                if (dex != null) {
                    bytes = (byte[]) getBytes.invoke(dex);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                log(e);
                return;
            }

            int length = 0;
            if (bytes != null) {
                length = bytes.length;
            }

            if (DEBUG) {
                log("loadClass " + cls.getName() + ", Dex: " + dex + ", length: " + length);
            }

            dexOutputTask.write(dex, bytes);
        }

        private static class DexOutputTask implements Runnable {
            // 字节码缓存，不立即写文件，避免写文件太慢导致ANR
            private final Queue<byte[]> byteSet = new LinkedList<>();
            // 跟踪哪些类已经解码过，避免重复写到文件中
            private final Set<Object> dexSet = new HashSet<>();
            // 保存线程自动关闭前的空闲时间，以毫秒计
            private final long idleMsToQuit;
            // dex文件保存目录
            private String savingDirectory;
            private Thread currentThread;
            private long threadId;

            DexOutputTask(String savingDirectory) {
                // 默认5分钟后没有数据，就自动结束
                this(savingDirectory, 300000);
            }

            DexOutputTask(String savingDirectory, long idleMsToQuit) {
                this.savingDirectory = savingDirectory;
                this.idleMsToQuit = idleMsToQuit;
            }

            @Override
            public void run() {
                currentThread = Thread.currentThread();
                threadId = currentThread.getId();

                File savingDir = new File(savingDirectory);
                if (!savingDir.exists()) {
                    if (!savingDir.mkdirs()) {
                        log("Can not mkdir " + savingDirectory);
                        return;
                    }
                }

                for (int i = 0; ; ) {
                    byte[] bytes;
                    synchronized (byteSet) {
                        bytes = byteSet.poll();
                        if (bytes == null) {
                            try {
                                long start = System.currentTimeMillis();
                                byteSet.wait(idleMsToQuit);
                                // 若是超时则退出，结束线程
                                if (System.currentTimeMillis() - start >= idleMsToQuit) {
                                    break;
                                } else {
                                    // 有新的数据，返回重新读取
                                    continue;
                                }
                            } catch (InterruptedException e) {
                                continue;
                            }
                        }
                    }

                    i++;
                    String targetFile =
                            savingDirectory + String.format("/%05d-%02d.dex", threadId, i);
                    log("Thread: " + threadId + ", File: " + targetFile);
                    try (FileOutputStream fileOutputStream = new FileOutputStream(targetFile)) {
                        fileOutputStream.write(bytes);
                    } catch (IOException e) {
                        log(e);
                    }
                }

                log("Thread: " + threadId + ", Dex size: " + dexSet.size());
                synchronized (byteSet) {
                    byteSet.clear();
                }
                synchronized (dexSet) {
                    dexSet.clear();
                }
                savingDirectory = null;
            }

            void write(Object dex, byte[] bytes) {
                if (dex == null || bytes == null) {
                    return;
                }

                if (currentThread == null || !currentThread.isAlive()) {
                    log("Thread " + threadId + " is not running");
                    return;
                }

                // 避免重复保存同一个dex
                synchronized (dexSet) {
                    if (dexSet.contains(dex)) {
                        return;
                    }
                    dexSet.add(dex);
                }

                synchronized (byteSet) {
                    byteSet.offer(bytes);
                    byteSet.notifyAll();
                }
            }
        }
    }
}
