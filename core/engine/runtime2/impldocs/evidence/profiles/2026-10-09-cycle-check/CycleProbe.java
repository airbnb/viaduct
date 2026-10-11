import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import viaduct.engine.runtime2.resolution.framework.CycleSlot;
import viaduct.engine.runtime2.resolution.framework.CycleSlotKind;
import viaduct.engine.runtime2.resolution.framework.CycleTask;

public class CycleProbe {
  static volatile boolean active;
  static final IdentityHashMap<Object, Graph> graphs = new IdentityHashMap<>();
  static int invocation;
  static class Graph {
    long reads, knownReads, adds, searches, nodesVisited, edgesVisited, throwsCount, currentSearch, maxSearch;
    Set<CycleTask> tasks = new HashSet<>();
    Set<CycleSlot> writers = new HashSet<>();
    Set<Read> readsSet = new HashSet<>();
    Set<Edge> edges = new HashSet<>();
    EnumMap<CycleSlotKind,Long> kinds = new EnumMap<>(CycleSlotKind.class);
  }
  record Read(CycleTask task, CycleSlot slot) {}
  record Edge(CycleTask reader, CycleTask writer) {}
  static Graph graph(Object state) { return graphs.computeIfAbsent(state, x -> new Graph()); }
  public static synchronized void begin() { graphs.clear(); active=true; }
  public static synchronized void end() {
    active=false; long reads=0,uniqueReads=0,writers=0,adds=0,edges=0,searches=0,visited=0,scanned=0,tasks=0,failed=0,unowned=0,knownReads=0;
    long maxSearch=0,readerIndexPairs=0;
    for (Object state:graphs.keySet()) { try { var f=state.getClass().getDeclaredField("readersBySlot");f.setAccessible(true);for (Object readers:((Map<?,?>)f.get(state)).values()) readerIndexPairs+=((Set<?>)readers).size(); } catch(ReflectiveOperationException ex) { throw new IllegalStateException(ex); } }
    EnumMap<CycleSlotKind,Long> kinds = new EnumMap<>(CycleSlotKind.class);
    for (Graph g: graphs.values()) {
      maxSearch=Math.max(maxSearch,g.maxSearch);reads+=g.reads; knownReads+=g.knownReads; uniqueReads+=g.readsSet.size(); writers+=g.writers.size(); adds+=g.adds; edges+=g.edges.size();
      searches+=g.searches; visited+=g.nodesVisited; scanned+=g.edgesVisited; tasks+=g.tasks.size(); failed+=g.throwsCount;
      for (Read r:g.readsSet) if (!g.writers.contains(r.slot())) unowned++;
      g.kinds.forEach((k,v)->kinds.merge(k,v,Long::sum));
    }
    System.out.println("CYCLE_DIAGNOSTIC invocation="+(++invocation)+" graphs="+graphs.size()+" readCalls="+reads+" readsWithKnownWriter="+knownReads+" distinctReaderSlots="+uniqueReads+" writerSlots="+writers+" addReadCalls="+adds+" distinctEdges="+edges+" distinctTasks="+tasks+" searches="+searches+" bfsDequeues="+visited+" bfsEdgesScanned="+scanned+" failedAddReads="+failed+" distinctReadsWithoutWriter="+unowned+" maxBfsDequeues="+maxSearch+" readerIndexPairs="+readerIndexPairs+" slotKinds="+kinds);
    graphs.clear();
  }
  public static synchronized void writer(Object state, CycleSlot slot, CycleTask task) {
    if (!active) return; Graph g=graph(state); g.writers.add(slot); g.tasks.add(task);
  }
  public static synchronized void read(Object state, CycleTask task, CycleSlot slot) {
    if (!active) return; Graph g=graph(state);g.reads++;if(g.writers.contains(slot))g.knownReads++;g.tasks.add(task);g.readsSet.add(new Read(task,slot));g.kinds.merge(slot.getKind(),1L,Long::sum);
  }
  public static synchronized void edge(Object state,CycleTask reader,CycleTask writer) {
    if (!active) return; Graph g=graph(state);g.adds++;g.tasks.add(reader);g.tasks.add(writer);g.edges.add(new Edge(reader,writer));
  }
  public static synchronized void search(Object state) { if(active) { Graph g=graph(state);g.searches++;g.currentSearch=0; } }
  public static synchronized void visit(Object state) { if(active) { Graph g=graph(state);g.nodesVisited++;g.maxSearch=Math.max(g.maxSearch,++g.currentSearch); } }
  public static synchronized void scan(Object state) { if(active)graph(state).edgesVisited++; }
  public static synchronized void failed(Object state) { if(active)graph(state).throwsCount++; }
  public static void premain(String arguments,Instrumentation inst) {
    System.err.println("CYCLE_AGENT_STARTED"); inst.addTransformer(new ClassFileTransformer() {
      public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
        if (!name.equals("viaduct/engine/runtime2/resolution/framework/CycleCheckStateImpl") && !name.equals("viaduct/engine/runtime2/resolution/ResolverBenchmark")) return null;
        System.err.println("CYCLE_TRANSFORM "+name); ClassReader cr=new ClassReader(bytes);ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9,cw) {
          public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions) {
            MethodVisitor parent=super.visitMethod(access,method,desc,sig,exceptions);
            return new MethodVisitor(Opcodes.ASM9,parent) {
              void call0(String probe) { super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"CycleProbe",probe,"(Ljava/lang/Object;)V",false); }
              public void visitCode() {
                super.visitCode();
                if (name.endsWith("ResolverBenchmark") && method.equals("overhead")) super.visitMethodInsn(Opcodes.INVOKESTATIC,"CycleProbe","begin","()V",false);
                if (name.endsWith("CycleCheckStateImpl")) {
                  if (method.equals("registerWriter") || method.equals("cycleCheck") || method.equals("addRead")) {
                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitVarInsn(Opcodes.ALOAD,2);
                    String probe=method.equals("registerWriter")?"writer":method.equals("cycleCheck")?"read":"edge";
                    String params=probe.equals("writer")?"Lviaduct/engine/runtime2/resolution/framework/CycleSlot;Lviaduct/engine/runtime2/resolution/framework/CycleTask;":probe.equals("read")?"Lviaduct/engine/runtime2/resolution/framework/CycleTask;Lviaduct/engine/runtime2/resolution/framework/CycleSlot;":"Lviaduct/engine/runtime2/resolution/framework/CycleTask;Lviaduct/engine/runtime2/resolution/framework/CycleTask;";
                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"CycleProbe",probe,"(Ljava/lang/Object;"+params+")V",false);
                  }
                  if (method.equals("pathFrom")) call0("search");
                }
              }
              public void visitMethodInsn(int opcode,String owner,String target,String descriptor,boolean iface) {
                super.visitMethodInsn(opcode,owner,target,descriptor,iface);
                if (method.equals("pathFrom") && owner.equals("kotlin/collections/ArrayDeque") && target.equals("removeFirst")) call0("visit");
                if (method.equals("pathFrom") && owner.equals("java/util/Iterator") && target.equals("next")) call0("scan");
              }
              public void visitInsn(int opcode) {
                if (name.endsWith("ResolverBenchmark") && method.equals("overhead") && (opcode>=Opcodes.IRETURN && opcode<=Opcodes.RETURN)) super.visitMethodInsn(Opcodes.INVOKESTATIC,"CycleProbe","end","()V",false);
                if (method.equals("addRead") && opcode==Opcodes.ATHROW) call0("failed");
                super.visitInsn(opcode);
              }
            };
          }
        },0);
        return cw.toByteArray();
      }
    });
  }
}
