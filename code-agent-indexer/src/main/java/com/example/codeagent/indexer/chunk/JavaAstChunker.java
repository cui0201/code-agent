package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 基于 JavaParser 的 AST 结构化切块器。
 *
 * <p>相对 P0 的定长窗口切块，它解决三个问题：
 * <ol>
 *   <li><b>不会把一个方法从中间切断</b>，方法块是完整的方法；</li>
 *   <li><b>块的 ID 稳定</b>：方法用 {@code 全限定名#方法签名}，行号变化不影响引用；</li>
 *   <li><b>块带有结构信息</b>：注解、所属类、参数签名都进入元数据，
 *       为后续的关键词检索与符号图打下基础。</li>
 * </ol>
 *
 * <h2>两类切块的取舍</h2>
 * <ul>
 *   <li><b>方法块</b>：内容是源码的逐字片段，行号区间精确，
 *       引用可以直接落到具体行，可靠性最高；</li>
 *   <li><b>类块</b>：内容是<b>合成摘要</b>（类声明 + 字段声明 + 方法签名，不含方法体），
 *       行号区间取整个类的真实范围。合成内容是为了回答"这个类负责什么"这类问题，
 *       同时避免把整个类（可能上千行）塞进上下文。
 *       这类块在元数据里标记 {@code synthesized=true}，以便上层区分。</li>
 * </ul>
 *
 * <p>解析失败时自动降级为定长窗口切块，保证索引不会因为一个语法文件整体失败。
 */
public class JavaAstChunker implements Chunker {

    private static final Logger log = LoggerFactory.getLogger(JavaAstChunker.class);

    private static final String LANGUAGE = "java";

    /** 类块中最多列出多少个方法签名，避免超大类的摘要本身超长。 */
    private static final int MAX_METHOD_SIGNATURES = 40;

    private final Chunker fallback;

    private final JavaParser parser;

    public JavaAstChunker(Chunker fallback) {
        this.fallback = fallback;
        // 必须显式指定语言级别：默认级别不支持 record 等新语法，
        // 会把好好的源文件误判为语法错误并降级。
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public static JavaAstChunker withLineWindowFallback(int windowLines, int overlapLines) {
        return new JavaAstChunker(new LineWindowChunker(windowLines, overlapLines));
    }

    @Override
    public List<Chunk> chunk(SourceFile file) {
        final CompilationUnit unit;
        try {
            ParseResult<CompilationUnit> result = parser.parse(file.content());
            if (result.getResult().isEmpty()) {
                log.warn("解析失败，降级为定长窗口切块: {} ({})",
                        file.path(), firstProblem(result));
                return fallback.chunk(file);
            }
            unit = result.getResult().get();
        } catch (RuntimeException e) {
            log.warn("解析异常，降级为定长窗口切块: {} ({})", file.path(), e.getMessage());
            return fallback.chunk(file);
        }

        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        String[] lines = file.content().split("\n", -1);

        List<Chunk> chunks = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            collectType(file, lines, type, packageName, null, chunks);
        }
        if (chunks.isEmpty()) {
            // module-info.java、package-info.java 这类没有类型声明的文件
            return fallback.chunk(file);
        }
        return chunks;
    }

    /** 递归收集一个类型及其嵌套类型的切块。 */
    private void collectType(SourceFile file,
                             String[] lines,
                             TypeDeclaration<?> type,
                             String packageName,
                             String parentFqn,
                             List<Chunk> chunks) {
        String simpleName = type.getNameAsString();
        String fqn = parentFqn == null
                ? qualify(packageName, simpleName)
                : parentFqn + "." + simpleName;

        int typeBegin = beginLine(type);
        int typeEnd = endLine(type);

        chunks.add(classSummaryChunk(file, type, fqn, parentFqn, typeBegin, typeEnd));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration method) {
                chunks.add(methodChunk(file, lines, method, fqn, parentFqn,
                        method.getNameAsString(), methodSignature(method)));
            } else if (member instanceof ConstructorDeclaration constructor) {
                chunks.add(methodChunk(file, lines, constructor, fqn, parentFqn,
                        simpleName, constructorSignature(constructor, simpleName)));
            } else if (member instanceof TypeDeclaration<?> nested) {
                collectType(file, lines, nested, packageName, fqn, chunks);
            }
        }
    }

    /** 类级摘要块：类声明 + 字段 + 方法签名，行号区间取整个类的真实范围。 */
    private Chunk classSummaryChunk(SourceFile file,
                                    TypeDeclaration<?> type,
                                    String fqn,
                                    String parentFqn,
                                    int beginLine,
                                    int endLine) {
        Map<String, Object> metadata = baseMetadata(file, "ast-class");
        metadata.put("fqn", fqn);
        metadata.put("simpleName", type.getNameAsString());
        metadata.put("synthesized", true);
        metadata.put("declaration", declarationOf(type));
        if (parentFqn != null) {
            metadata.put("parentFqn", parentFqn);
        }

        return new Chunk(fqn, file.path(), ChunkKind.CLASS,
                renderClassSummary(type, fqn), beginLine, endLine, metadata);
    }

    /** 方法级块：源码的逐字片段，引用可直接落到具体行。 */
    private Chunk methodChunk(SourceFile file,
                              String[] lines,
                              Node node,
                              String typeFqn,
                              String parentFqn,
                              String name,
                              String signature) {
        int begin = beginLine(node);
        int end = endLine(node);
        String content = extractLines(lines, begin, end);

        String id = typeFqn + "#" + signature;
        Map<String, Object> metadata = baseMetadata(file, "ast-method");
        metadata.put("fqn", id);
        metadata.put("simpleName", name);
        metadata.put("signature", signature);
        metadata.put("parentFqn", typeFqn);
        metadata.put("synthesized", false);
        metadata.put("declaration", declarationOfMember(node));
        List<String> annotations = annotationsOf(node);
        if (!annotations.isEmpty()) {
            metadata.put("annotations", annotations);
        }

        return new Chunk(id, file.path(), ChunkKind.METHOD, content, begin, end, metadata);
    }

    /** 渲染类摘要：保留 Javadoc、类声明、字段声明与方法签名，丢弃方法体。 */
    private String renderClassSummary(TypeDeclaration<?> type, String fqn) {
        StringBuilder builder = new StringBuilder();
        javadocOf(type).ifPresent(text -> builder.append(text).append('\n'));
        builder.append(declarationOf(type)).append(" {\n");

        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                for (VariableDeclarator variable : field.getVariables()) {
                    fields.add("    " + field.getModifiers().stream()
                            .map(modifier -> modifier.getKeyword().asString())
                            .collect(Collectors.joining(" "))
                            + " " + variable.getType().asString()
                            + " " + variable.getNameAsString() + ";");
                }
            } else if (member instanceof MethodDeclaration method) {
                if (methods.size() < MAX_METHOD_SIGNATURES) {
                    methods.add("    " + renderMethodHeader(method) + ";");
                }
            } else if (member instanceof ConstructorDeclaration constructor) {
                if (methods.size() < MAX_METHOD_SIGNATURES) {
                    methods.add("    " + renderConstructorHeader(constructor, type.getNameAsString())
                            + ";");
                }
            }
        }

        if (!fields.isEmpty()) {
            builder.append("    // fields\n");
            fields.forEach(line -> builder.append(line).append('\n'));
        }
        if (!methods.isEmpty()) {
            builder.append("    // methods\n");
            methods.forEach(line -> builder.append(line).append('\n'));
        }
        builder.append("}");
        return builder.toString();
    }

    /** 生成类/接口/枚举/记录的声明行，例如 {@code public class OrderService}。 */
    private String declarationOf(TypeDeclaration<?> type) {
        String modifiers = type.getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .collect(Collectors.joining(" "));
        String prefix = modifiers.isEmpty() ? "" : modifiers + " ";

        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            String kind = declaration.isInterface() ? "interface" : "class";
            String typeParams = renderTypeParameters(declaration.getTypeParameters());
            String extend = declaration.getExtendedTypes().isEmpty()
                    ? "" : " extends " + renderTypes(declaration.getExtendedTypes());
            String implement = declaration.getImplementedTypes().isEmpty()
                    ? "" : " implements " + renderTypes(declaration.getImplementedTypes());
            return prefix + kind + " " + declaration.getNameAsString()
                    + typeParams + extend + implement;
        }
        if (type instanceof EnumDeclaration declaration) {
            String implement = declaration.getImplementedTypes().isEmpty()
                    ? "" : " implements " + renderTypes(declaration.getImplementedTypes());
            return prefix + "enum " + declaration.getNameAsString() + implement;
        }
        if (type instanceof RecordDeclaration declaration) {
            String params = renderTypes(declaration.getParameters());
            String implement = declaration.getImplementedTypes().isEmpty()
                    ? "" : " implements " + renderTypes(declaration.getImplementedTypes());
            return prefix + "record " + declaration.getNameAsString()
                    + "(" + params + ")" + implement;
        }
        if (type instanceof AnnotationDeclaration declaration) {
            return prefix + "@interface " + declaration.getNameAsString();
        }
        return prefix + type.getNameAsString();
    }

    private String renderTypeParameters(List<TypeParameter> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return "<" + parameters.stream()
                .map(TypeParameter::asString)
                .collect(Collectors.joining(", ")) + ">";
    }

    private String renderTypes(List<? extends Node> types) {
        return types.stream().map(Node::toString).collect(Collectors.joining(", "));
    }

    private String methodSignature(MethodDeclaration method) {
        String parameters = method.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .collect(Collectors.joining(","));
        return method.getNameAsString() + "(" + parameters + ")";
    }

    private String constructorSignature(ConstructorDeclaration constructor, String simpleName) {
        String parameters = constructor.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .collect(Collectors.joining(","));
        return simpleName + "(" + parameters + ")";
    }

    /** 渲染带参数名的方法头，用于类摘要，例如 {@code Order createOrder(OrderRequest request)}。 */
    private String renderMethodHeader(MethodDeclaration method) {
        String parameters = method.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
        return method.getType().asString() + " " + method.getNameAsString() + "(" + parameters + ")";
    }

    private String renderConstructorHeader(ConstructorDeclaration constructor, String simpleName) {
        String parameters = constructor.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
        return simpleName + "(" + parameters + ")";
    }

    /** 渲染方法或构造函数的声明（含修饰符），写入元数据供检索展示。 */
    private String declarationOfMember(Node node) {
        if (node instanceof MethodDeclaration method) {
            String modifiers = renderModifiers(method.getModifiers());
            return modifiers + renderMethodHeader(method);
        }
        if (node instanceof ConstructorDeclaration constructor) {
            String modifiers = renderModifiers(constructor.getModifiers());
            return modifiers + renderConstructorHeader(constructor, constructor.getNameAsString());
        }
        return node.toString();
    }

    private String renderModifiers(List<com.github.javaparser.ast.Modifier> modifiers) {
        if (modifiers.isEmpty()) {
            return "";
        }
        return modifiers.stream()
                .map(modifier -> modifier.getKeyword().asString())
                .collect(Collectors.joining(" ")) + " ";
    }

    private Optional<String> javadocOf(Node node) {
        if (node instanceof NodeWithJavadoc<?> withJavadoc) {
            return withJavadoc.getJavadocComment().map(comment -> comment.getContent().trim());
        }
        return Optional.empty();
    }

    private List<String> annotationsOf(Node node) {
        if (node instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> withAnnotations) {
            return withAnnotations.getAnnotations().stream()
                    .map(AnnotationExpr::toString)
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> baseMetadata(SourceFile file, String chunkerName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", LANGUAGE);
        metadata.put("fileHash", file.hash());
        metadata.put("chunker", chunkerName);
        return metadata;
    }

    /**
     * 起始行，包含挂在该节点上的注释。
     *
     * <p>JavaParser 的 {@code getRange()} 不含注释，而 {@code getBegin()} 含注释。
     * 为了让引用行号能覆盖 Javadoc，这里两者取较小值。
     */
    private static int beginLine(Node node) {
        int nodeBegin = node.getRange().map(range -> range.begin.line).orElse(1);
        int commentBegin = node.getComment()
                .flatMap(Node::getRange)
                .map(range -> range.begin.line)
                .orElse(nodeBegin);
        return Math.min(nodeBegin, commentBegin);
    }

    private static int endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElseGet(() -> beginLine(node));
    }

    private static String extractLines(String[] lines, int beginLine, int endLine) {
        int from = Math.max(0, beginLine - 1);
        int to = Math.min(lines.length, endLine);
        if (from >= to) {
            return "";
        }
        return String.join("\n", Arrays.copyOfRange(lines, from, to));
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName == null || packageName.isBlank()
                ? simpleName
                : packageName + "." + simpleName;
    }

    private static String firstProblem(ParseResult<CompilationUnit> result) {
        return result.getProblems().stream()
                .findFirst()
                .map(problem -> problem.getVerboseMessage())
                .orElse("未知原因");
    }
}
