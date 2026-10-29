package com.minsu.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识库持久化仓库 —— 用 SQLite（纯 JDBC，无 ORM）把「联网沉淀的知识」落盘，重启不丢。
 *
 * <p>数据库文件位于后端运行目录下的 {@code data/kb.db}（可被 {@code app.kb.db-path} 覆盖）。
 * 表结构：{@code kb_docs(id TEXT PRIMARY KEY, category TEXT, content TEXT, created_at TEXT)}。
 *
 * <p>设计原则（保持轻后端定位）：
 * <ul>
 *   <li>不引入 JPA / MyBatis，直接 JDBC 操作，避免额外框架；</li>
 *   <li>只持久化「沉淀的知识」（联网搜来的新内容），内置 15 条种子在 {@link KbStore} 内存里维护；</li>
 *   <li>检索仍走内存（{@link RagService}），SQLite 只负责「启动加载 + 沉淀写回」。</li>
 * </ul>
 */
@Component
public class KbRepository {

    private static final Logger log = LoggerFactory.getLogger(KbRepository.class);

    /** 数据库文件路径（可配置，默认后端运行目录 data/kb.db）。 */
    private final String dbPath;

    public KbRepository(@org.springframework.beans.factory.annotation.Value("${app.kb.db-path:data/kb.db}") String dbPath) {
        this.dbPath = dbPath;
        init();
    }

    /** 建库建表（若不存在），并在首次启动时把种子文档写入。 */
    private void init() {
        try {
            Path file = Path.of(dbPath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS kb_docs (
                            id         TEXT PRIMARY KEY,
                            category   TEXT NOT NULL,
                            content    TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """);
            }
            log.info("SQLite 知识库已就绪：{}", dbPath);
        } catch (Exception e) {
            // 持久化失败不阻断服务启动，退化为「仅内存」模式
            log.error("SQLite 知识库初始化失败，退化为仅内存模式：{}", e.getMessage());
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /**
     * 读取全部已持久化的文档（按 created_at 升序，保证加载顺序稳定）。
     */
    public List<KbDocument> loadAll() {
        List<KbDocument> list = new ArrayList<>();
        String sql = "SELECT id, category, content FROM kb_docs ORDER BY created_at ASC, rowid ASC";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new KbDocument(rs.getString("id"), rs.getString("category"), rs.getString("content")));
            }
        } catch (SQLException e) {
            log.error("读取知识库失败：{}", e.getMessage());
        }
        return list;
    }

    /**
     * 插入一条文档（幂等：同 id 已存在则忽略，返回是否真正插入）。
     *
     * @return {@code true} 表示新插入；{@code false} 表示已存在（去重）或写入失败
     */
    public boolean insert(String id, String category, String content) {
        String sql = "INSERT OR IGNORE INTO kb_docs (id, category, content, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, category);
            ps.setString(3, content);
            ps.setString(4, String.valueOf(System.currentTimeMillis()));
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            log.error("写入知识库失败：{}", e.getMessage());
            return false;
        }
    }

    /** 判断内容是否已存在（按 content 精确去重，避免同内容重复沉淀）。 */
    public boolean existsByContent(String content) {
        String sql = "SELECT 1 FROM kb_docs WHERE content = ? LIMIT 1";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("查询知识库失败：{}", e.getMessage());
            return false;
        }
    }
}
