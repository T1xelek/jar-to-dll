import java.io.File;
import java.io.PrintWriter;
import java.security.ProtectionDomain;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Set;

public class ForgeInjector extends Thread {
    private byte[][] classes;

    private ForgeInjector(byte[][] classes) {
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        new Thread(new ForgeInjector(classes)).start();
    }

    private static Class tryGetClass(PrintWriter writer, ClassLoader cl, String... names) throws ClassNotFoundException {
    	ClassNotFoundException lastException = null;
    	for (String name : names) {
    		try {
    			return cl.loadClass(name);
    		} catch (ClassNotFoundException e) {
    			lastException = e;
    		}
    	}
    	throw lastException;
    }

    private void bypass() {
        try {
            deencapsulate();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theInternalUnsafe");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private MethodHandles.Lookup lookup(Class<?> moduleClass) {
        try {
            Class<?> reflectionFactoryClass = Class.forName("sun.reflect.ReflectionFactory");
            Method getReflectionFactoryMethod = reflectionFactoryClass.getDeclaredMethod("getReflectionFactory");
            Object reflectionFactory = getReflectionFactoryMethod.invoke(null);

            Method newConstructorForSerializationMethod = reflectionFactoryClass.getDeclaredMethod("newConstructorForSerialization", Class.class, Constructor.class);

            Constructor<?> ctor = (Constructor<?>) newConstructorForSerializationMethod.invoke(reflectionFactory, MethodHandles.Lookup.class, MethodHandles.Lookup.class.getDeclaredConstructor(Class.class));
            return (MethodHandles.Lookup) ctor.newInstance(moduleClass);
        } catch (ReflectiveOperationException e) {
            Throwable t;
            if (e instanceof InvocationTargetException) {
                t = ((InvocationTargetException) e).getTargetException();
            } else {
                t = e;
            }
            throw new IllegalStateException(t);
        }
    }

    @SuppressWarnings("unchecked")
    private void deencapsulate() throws Exception {
        Method getModuleMethod;
        try {
            getModuleMethod = Class.class.getDeclaredMethod("getModule");
        } catch (Throwable e) {
            return;
        }

        Set<Object> modules = new HashSet<>();

        Object moduleBase = getModuleMethod.invoke(ForgeInjector.class);
        Class<?> moduleClass = Class.forName("java.lang.Module");

        Method getLayerMethod = moduleClass.getDeclaredMethod("getLayer");
        Object baseLayer = getLayerMethod.invoke(moduleBase);
        Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");

        Method modulesMethod = moduleLayerClass.getDeclaredMethod("modules");
        if (baseLayer != null) {
            modules.addAll((Set<?>) modulesMethod.invoke(baseLayer));
        }
        Method bootMethod = moduleLayerClass.getDeclaredMethod("boot");
        Object boot = bootMethod.invoke(null);

        modules.addAll((Set<?>) modulesMethod.invoke(boot));

        Method getUnnamedModuleMethod = ClassLoader.class.getDeclaredMethod("getUnnamedModule");
        for (ClassLoader cl = ForgeInjector.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            modules.add(getUnnamedModuleMethod.invoke(cl));
        }
        try {
            MethodHandle export = lookup(moduleClass).findVirtual(moduleClass, "implAddOpens", MethodType.methodType(void.class, String.class));
            Method getPackages = moduleClass.getDeclaredMethod("getPackages");
            for (Object module : modules) {
                for (String name : (Set<String>) getPackages.invoke(module)) {
                    export.invoke(module, name);
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Could not export packages", t);
        }
    }

    @Override
    public void run() {
        bypass();
        try (PrintWriter writer = new PrintWriter(System.getProperty("user.home") + File.separator + "log.txt", "UTF-8")) {
            writer.println("Starting!");
            writer.flush();
            try {
                ClassLoader cl = null;
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    ClassLoader threadLoader;
                    if (thread == null || thread.getContextClassLoader() == null || (threadLoader = thread.getContextClassLoader()).getClass() == null || 
                        threadLoader.getClass().getName() == null) continue;
                    String loaderName = threadLoader.getClass().getName();
                    writer.println("Thread: " + thread.getName() + " [" + loaderName + "]");
                    writer.flush();
                    if (!loaderName.contains("LaunchClassLoader") && !loaderName.contains("RelaunchClassLoader") && !loaderName.contains("TransformingClassLoader")) continue;
                    cl = threadLoader;
                    break;
                }
                if (cl == null) {
                    throw new Exception("ClassLoader is null");
                }
                this.setContextClassLoader(cl);
                Method loadMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
                loadMethod.setAccessible(true);
                writer.println("Loading " + classes.length + " classes");
                writer.flush();
                Class<?> mainClass = null;
                for (byte[] classData : classes) {
                    if (classData == null) {
                        throw new Exception("classData is null");
                    }
                    if (cl.getClass() == null) {
                        throw new Exception("getClass() is null");
                    }
                    try {
                        Class tClass = null;
                        try {
                            tClass = (Class)loadMethod.invoke(cl, null, classData, 0, classData.length, cl.getClass().getProtectionDomain());
                        } catch (Throwable e) {
                            if (!(e instanceof LinkageError)) {
                                throw e;
                            }

                            if (e.getMessage().contains("duplicate class definition for name: ")) {
                                String className = e.getMessage().split("\"")[1];
                                tClass = cl.loadClass(className.replace('/', '.'));
                                writer.println("It is recommended to remove " + className + ".class from your input.jar");
                            }
                        }
                        if (tClass == null || !tClass.getName().equals("cc.monoline.Injector")) continue;
                        mainClass = tClass;
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        throw new Exception("Exception on defineClass", e);
                    }
                }
                writer.println(classes.length + " loaded successfully");
                writer.flush();
                if (mainClass == null) {
                    throw new Exception("mainClass is null");
                } else {
                    mainClass.newInstance();
                    writer.println("Successfully injected");
                    writer.flush();
                }
            }
            catch (Throwable e) {
                e.printStackTrace(writer);
                writer.flush();
            }
            writer.close();
        }
        catch (Throwable e) {
            e.printStackTrace();
        }
    }
}

