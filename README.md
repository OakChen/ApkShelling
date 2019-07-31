# 一个脱简单壳的Xposed模块

## 用法

- 搭建Xposed环境
- 将XposedEntry.java中的targetPackage改为需要脱壳的包
- 编译本模块并安装到手机
- 激活模块后，打开目标应用，随便操作一会儿，等待/data/data/packageName下产生dex文件，可查看logcat看进展：logcat -s Xposed
- 把dex文件都复制到电脑上，用jadx反编译，如果有反编译失败的，先用dex2jar转成jar再用jadx反编译可解决部分失败情况

## 原理

在加载包的时候，匹配是否目标包名及是否加壳，如果是，就hook java.lang.ClassLoader类的loadClass方法，应用如下操作：

- 获得loadClass返回的Class对象
- 反射调用Class对象的getDex方法获得Dex对象
- 反射调用Dex的getBytes方法得到字节集
- 将Dex对象和字节集提交给写文件的线程
- 线程异步写字节集到文件中，避免了同步写可能导致ANR

## 限制
- 只在Android 5.1.1版本的手机上验证过，其它的版本不一定有对应的getBytes和geDex方法
- 只验证过腾讯乐固、360加固、梆梆加固、百度加固的免费加固工具，都可以脱掉，付费版本没有用过

## 参考
原参考项目：https://github.com/a813630449/dumpDex

