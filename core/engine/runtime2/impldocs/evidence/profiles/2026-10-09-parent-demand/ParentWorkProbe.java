import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
public class ParentWorkProbe {
    public static boolean active;
    public static long requests, normalizedElements;
    public static void begin() { requests = normalizedElements = 0; active = true; }
    public static void request() { if (active) requests++; }
    public static void normalize(int size) { if (active) normalizedElements += size; }
    public static void end(String shape, int depth, int checked, int unchecked) {
        active = false;
        System.out.println("PARENT_COUNT shape=" + shape + " depth=" + depth + " requests=" + requests + " normalizedElements=" + normalizedElements + " checked=" + checked + " unchecked=" + unchecked);
    }
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("viaduct/engine/runtime2/resolution/ParentRequest") && !name.equals("viaduct/engine/runtime2/resolution/ParentDemandNormalizer")) return null;
                var reader = new ClassReader(bytes);
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions)) {
                            public void visitCode() {
                                super.visitCode();
                                if (name.endsWith("ParentRequest") && method.equals("<init>")) super.visitMethodInsn(Opcodes.INVOKESTATIC, "ParentWorkProbe", "request", "()V", false);
                                if (name.endsWith("ParentDemandNormalizer") && method.equals("normalize") && desc.startsWith("(Lviaduct/engine/runtime2/model/SelectionForest;")) {
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "viaduct/engine/runtime2/model/SelectionForest", "getSize", "()I", true);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "ParentWorkProbe", "normalize", "(I)V", false);
                                }
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
