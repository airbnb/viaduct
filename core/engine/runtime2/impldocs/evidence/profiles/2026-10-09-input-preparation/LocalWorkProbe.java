import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
public class LocalWorkProbe {
 public static volatile boolean active;
 public static AtomicLong prefixCopies=new AtomicLong(), fieldKeyReads=new AtomicLong(), awaitRegistrations=new AtomicLong(), completionSends=new AtomicLong();
 public static void begin(){prefixCopies.set(0);fieldKeyReads.set(0);awaitRegistrations.set(0);completionSends.set(0);active=true;}
 public static void prefix(int n){if(active)prefixCopies.addAndGet(n);}
 public static void field(){if(active)fieldKeyReads.incrementAndGet();}
 public static void registration(){if(active)awaitRegistrations.incrementAndGet();}
 public static void send(){if(active)completionSends.incrementAndGet();}
 public static void end(String name,int n){active=false;System.out.println("WORK_COUNT "+name+" width="+n+" prefixCopies="+prefixCopies+" fieldKeyReads="+fieldKeyReads+" awaitRegistrations="+awaitRegistrations+" completionSends="+completionSends);}
 public static void premain(String ignored,Instrumentation i){System.err.println("LOCAL_AGENT_STARTED");i.addTransformer(new ClassFileTransformer(){
  public byte[] transform(ClassLoader l,String name,Class<?> c,ProtectionDomain d,byte[] bytes){
   if(!name.equals("viaduct/engine/runtime2/model/MaterializeSelectionForestImpl")&&!name.equals("viaduct/engine/runtime2/resolution/framework/SharedPassiveValueResolutionLogic")&&!name.startsWith("viaduct/engine/runtime2/resolution/InclusionConditionActivationKt"))return null;
   System.err.println("LOCAL_TRANSFORM "+name);try {var cr=new ClassReader(bytes);var cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
   cr.accept(new ClassVisitor(Opcodes.ASM9,cw){public MethodVisitor visitMethod(int a,String m,String desc,String sig,String[] es){return new MethodVisitor(Opcodes.ASM9,super.visitMethod(a,m,desc,sig,es)){
    public void visitCode(){super.visitCode();if(name.endsWith("MaterializeSelectionForestImpl")&&m.equals("plus")){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,name,"getSize","()I",false);super.visitMethodInsn(Opcodes.INVOKESTATIC,"LocalWorkProbe","prefix","(I)V",false);}}
    public void visitMethodInsn(int op,String owner,String target,String dd,boolean itf){
     if(name.endsWith("SharedPassiveValueResolutionLogic")&&m.startsWith("materializePassiveFields")&&target.equals("getField")&&owner.contains("ObjectEngineResult"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"LocalWorkProbe","field","()V",false);
     if(name.contains("InclusionConditionActivationKt")&&target.equals("getOnAwait"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"LocalWorkProbe","registration","()V",false);
     if(name.contains("InclusionConditionActivationKt")&&target.equals("send"))super.visitMethodInsn(Opcodes.INVOKESTATIC,"LocalWorkProbe","send","()V",false);
     super.visitMethodInsn(op,owner,target,dd,itf);
    }
   };}},0);return cw.toByteArray();}catch(Throwable t){t.printStackTrace();throw t;}
  }
 });}
}
