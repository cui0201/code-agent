package com.example.codeagent.indexer.graph;

import com.example.codeagent.core.model.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用 Spoon 从源码构建符号图。
 *
 * <h2>为什么不能用 JavaParser 做这件事</h2>
 *
 * 切块只需要语法树，JavaParser 足够且快得多；
 * 但"谁调用了这个方法"需要类型解析：
 * 看到 orderService.createOrder(request)，必须知道 orderService 的声明类型是
 * com.acme.order.OrderService，才能把调用点连到正确的方法节点上。
 * JavaParser 的符号求解弱于此，Spoon 正是为此设计的。
 *
 * <h2>为什么用 noClasspath 模式</h2>
 *
 * 真实工程的依赖（Spring、三方库）常常不在索引机器上，完整解析会直接失败。
 * setNoClasspath(true) 让 Spoon 在解析不到类型时降级处理而不是报错，
 * 代价是部分调用点无法确定目标——这类边会被跳过。
 * 宁可少几条边，也不要错几条边：影响面分析给出错误的结论，比给不出结论更糟。
 *
 * <h2>为什么用 VirtualFile 而不是让 Spoon 自己扫目录</h2>
 *
 * 复用索引阶段已经读好的文件集合（同一套 include/exclude、同一份内容），
 * 避免"向量索引里的文件和图里的文件不一致"这种难查的问题；
 * 顺带也避开了 Spoon 自己去找 pom.xml 解析 classpath 的行为。
 */
public class SpoonGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(SpoonGraphBuilder.class);

    /** 编译级别要覆盖 record 等新语法，否则解析直接失败。 */
    private static final int COMPLIANCE_LEVEL = 21;

    /**
     * 从已读取的源文件构建图。
     *
     * @param files 本次索引的源文件；非 Java 文件会被忽略
     * @return 符号图；构建失败时返回空图而不抛异常
     */
    public SymbolGraph build(List<SourceFile> files) {
        SymbolGraph graph = new SymbolGraph();
        Launcher launcher = createLauncher();

        int javaFileCount = 0;
        for (SourceFile file : files) {
            if (!"java".equals(file.extension())) {
                continue;
            }
            launcher.addInputResource(new VirtualFile(file.content(), file.path()));
            javaFileCount++;
        }
        if (javaFileCount == 0) {
            log.info("没有 Java 源文件，跳过符号图构建");
            return graph;
        }

        final CtModel model;
        try {
            launcher.buildModel();
            model = launcher.getModel();
        } catch (RuntimeException e) {
            // 图是增强能力而非主链路：解析失败时记录原因并返回空图，
            // 让索引与检索照常可用。降级必须显式记录，不能静默。
            log.warn("符号图构建失败，本次不提供影响面分析: {}", e.getMessage());
            return graph;
        }

        // 从"编译单元 -> 声明类型"的映射出发，而不是遍历 model.getAllTypes()。
        // 原因是：用 VirtualFile 构建的模型里，元素的 position 是"部分位置"，
        // 调 position.getFile() 会抛 PartialSourcePosition 异常；
        // 而这张映射表的 key 就是我们传入的路径，拿文件路径最直接、最可靠。
        for (Map.Entry<String, CompilationUnit> entry
                : launcher.getFactory().CompilationUnit().getMap().entrySet()) {
            String filePath = normalizePath(entry.getKey());
            for (CtType<?> type : entry.getValue().getDeclaredTypes()) {
                collectType(graph, type, filePath);
            }
        }
        log.info("符号图构建完成: 节点={}, 边={}, Java 文件={}",
                graph.symbolCount(), graph.edgeCount(), javaFileCount);
        return graph;
    }

    private Launcher createLauncher() {
        Launcher launcher = new Launcher();
        // 类型解析不到也继续，避免三方依赖缺失导致整体失败
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(COMPLIANCE_LEVEL);
        // 图不需要注释信息，关掉能省不少内存
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setCopyResources(false);
        return launcher;
    }

    /** 供测试与诊断使用：当前构建器产出的边类型。 */
    public static Set<EdgeType> supportedEdgeTypes() {
        return Set.of(EdgeType.EXTENDS, EdgeType.IMPLEMENTS, EdgeType.CALLS, EdgeType.DECLARES);
    }

    /** 收集一个类型节点及其所有边。 */
    private void collectType(SymbolGraph graph, CtType<?> type, String filePath) {
        String typeKey = type.getQualifiedName();
        if (typeKey == null || typeKey.isBlank()) {
            return;
        }
        graph.addVertex(typeKey);
        graph.addSymbolFile(typeKey, filePath);
        collectTypeRelations(graph, type, typeKey, filePath);
        collectMethods(graph, type, typeKey, filePath);

        // 嵌套类型要单独遍历：它们与顶层类型一样能成为影响面分析的起点
        for (CtType<?> nested : type.getNestedTypes()) {
            collectType(graph, nested, filePath);
        }
    }

    /** 继承与实现关系。 */
    private void collectTypeRelations(SymbolGraph graph, CtType<?> type, String typeKey,
                                      String filePath) {
        // isShadow 表示这个类型只在引用里出现过、源码中并不存在（例如三方库类型）。
        // 把它连进图没有意义，反而会让影响面分析跑到工程外面去。
        // 注意：getSuperclass 与 getSuperInterfaces 来自 CtTypeInformation，
        // 定义在 CtType 上，不需要强转成 CtClass。
        CtTypeReference<?> superclass = type.getSuperclass();
        if (superclass != null && !superclass.isShadow()) {
            graph.addEdge(typeKey, superclass.getQualifiedName(),
                    EdgeType.EXTENDS, filePath, lineOf(type));
        }
        for (CtTypeReference<?> itf : type.getSuperInterfaces()) {
            if (itf != null && !itf.isShadow()) {
                graph.addEdge(typeKey, itf.getQualifiedName(),
                        EdgeType.IMPLEMENTS, filePath, lineOf(type));
            }
        }
    }

    /** 方法节点，以及方法体内的调用关系。 */
    private void collectMethods(SymbolGraph graph, CtType<?> type, String typeKey, String file) {

        for (CtMethod<?> method : type.getMethods()) {
            // getMethods() 会带上继承来的方法，这里只要本类型声明的
            if (!type.equals(method.getDeclaringType())) {
                continue;
            }
            List<String> paramTypes = parameterTypesOf(method);
            addMethodNode(graph, typeKey, method.getSimpleName(), paramTypes, file, lineOf(method));
            collectInvocations(graph, method, typeKey, method.getSimpleName(), paramTypes, file);
        }

        // 构造函数同样建节点：构造过程里的调用也是影响面的一部分。
        // 用 TypeFilter 而不是 CtClass.getConstructors()，这样 record/enum 也能覆盖，
        // 并用 declaringType 过滤掉嵌套类型的构造函数。
        for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
            if (!type.equals(constructor.getDeclaringType())) {
                continue;
            }
            String name = type.getSimpleName();
            List<String> paramTypes = parameterTypesOf(constructor);
            addMethodNode(graph, typeKey, name, paramTypes, file, lineOf(constructor));
            collectInvocations(graph, constructor, typeKey, name, paramTypes, file);
        }
    }

    /**
     * 建立"类型声明成员"这条边。
     *
     * <p>它让图能回答"这个方法属于哪个类"，
     * 也让以类为起点的查询（OrderService 下有哪些方法）能顺着 DECLARES 展开。
     */
    private void addMethodNode(SymbolGraph graph, String typeKey, String methodName,
                               List<String> paramTypes, String file, int line) {
        String methodKey = SymbolKeys.methodKey(typeKey, methodName, paramTypes);
        graph.addVertex(methodKey);
        graph.addSymbolFile(methodKey, file);
        graph.addEdge(typeKey, methodKey, EdgeType.DECLARES, file, line);
    }

    /** 收集方法体里的调用，连成 CALLS 边。 */
    private void collectInvocations(SymbolGraph graph, CtExecutable<?> executable,
                                    String typeKey, String methodName,
                                    List<String> paramTypes, String file) {
        String callerKey = SymbolKeys.methodKey(typeKey, methodName, paramTypes);
        for (CtInvocation<?> invocation : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> reference = invocation.getExecutable();
            if (reference == null || reference.getDeclaringType() == null) {
                continue;
            }
            // 解析不到声明类型时（noClasspath 下的正常情况）跳过，不猜目标：
            // 猜错会让影响面分析给出错误的"受影响范围"
            String declaringName = reference.getDeclaringType().getQualifiedName();
            if (declaringName == null || declaringName.isBlank()) {
                continue;
            }
            List<String> calleeParams = new ArrayList<>();
            for (CtTypeReference<?> param : reference.getParameters()) {
                calleeParams.add(param.getQualifiedName());
            }
            String calleeKey = SymbolKeys.methodKey(declaringName,
                    reference.getSimpleName(), calleeParams);
            graph.addEdge(callerKey, calleeKey, EdgeType.CALLS, file, lineOf(invocation));
        }
    }

    private List<String> parameterTypesOf(CtExecutable<?> executable) {
        List<String> types = new ArrayList<>();
        executable.getParameters().forEach(parameter -> types.add(
                parameter.getType() == null ? "" : parameter.getType().getQualifiedName()));
        return types;
    }

    /**
     * 把路径统一成索引里使用的相对路径。
     *
     * <p>Spoon 可能把入参规范化成绝对路径，这里裁到 src/ 之后，
     * 保证图里的文件路径与切块元数据里的路径一致——否则
     * "影响面涉及哪些文件"会和"检索命中的文件"对不上。
     */
    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int marker = normalized.indexOf("src/");
        return marker >= 0 ? normalized.substring(marker) : normalized;
    }

    /**
     * 元素所在行；定位不到时返回 -1，原样保留以便区分"第 0 行"。
     *
     * <p>用 try/catch 包住：VirtualFile 模型下的位置是"部分位置"，
     * 某些元素取行号会抛异常。行号只是辅助信息（用来跳转复核），
     * 拿不到就不给，不该让整张图构建失败。
     */
    private int lineOf(CtElement element) {
        if (element == null || element.getPosition() == null) {
            return -1;
        }
        try {
            return element.getPosition().getLine();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
