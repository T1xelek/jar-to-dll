import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

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

    @Override
    public void run() {
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
