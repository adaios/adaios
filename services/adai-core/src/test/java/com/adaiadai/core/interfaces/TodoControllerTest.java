package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TodoAppService;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.todo.Todo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TodoController — 待办清单 API 测试（RFC 20260917）。
 * <p>
 * 覆盖 5 个端点 + 输入校验人话 + **无插件门控回归**（旧 /project/tasks 的三处 403 随 project 插件退役：
 * 本 Controller 不再依赖 PluginService，任意登录用户可建/改/完成/删）。
 */
class TodoControllerTest {

    private TodoAppService todoService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        todoService = new TodoAppService(new TodoFileRepository(new InMemoryFileStorage()),
                mock(MemoryService.class));
        TodoController controller = new TodoController(todoService);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private Todo seed(String userId, String title, LocalDate due) {
        return todoService.createTodo(userId, title, due, null);
    }

    @Test
    void listTodos_returnsList() throws Exception {
        seed("default", "买菜", LocalDate.of(2026, 9, 20));

        mvc.perform(get("/api/v1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("买菜"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].due").value("2026-09-20"));
    }

    @Test
    void listTodos_forwardsStatusAndUserId() throws Exception {
        seed("alice", "alice 的待办", null);
        seed("bob", "bob 的待办", null);

        mvc.perform(get("/api/v1/todos").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("alice 的待办"));
    }

    @Test
    void createTodo_returnsTodo() throws Exception {
        mvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"给妈打个电话","due":"2026-09-20"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("给妈打个电话"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.due").value("2026-09-20"));
    }

    @Test
    void createTodo_withoutPluginPlugin_isNotForbidden() throws Exception {
        // RFC 20260917 回归：待办是 Kernel builtin，无插件用户（family/alice）照样能建——
        // 旧 /project/tasks 的 requireProjectPlugin 403 已随插件一起删掉
        mvc.perform(post("/api/v1/todos")
                        .header("X-User-Id", "family")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"没有插件的用户也能加\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createTodo_blankTitle_400HumanReadable() throws Exception {
        mvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("待办内容不能为空"));
    }

    @Test
    void createTodo_badDue_400HumanReadable() throws Exception {
        mvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"买菜\",\"due\":\"明天\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("到期日要写成 2026-09-20 这样"));
    }

    @Test
    void createTodo_withoutDue_isAllowed() throws Exception {
        mvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"不设期限\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updateTodo_statusAndTitle() throws Exception {
        Todo todo = seed("default", "买菜", null);

        mvc.perform(put("/api/v1/todos/" + todo.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.title").value("买菜"));
    }

    @Test
    void updateTodo_emptyDue_clearsDue_theNullDue_keepsIt() throws Exception {
        Todo todo = seed("default", "交房租", LocalDate.of(2026, 9, 25));

        // 不传 due（null）= 保持原值
        mvc.perform(put("/api/v1/todos/" + todo.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"交房租（改标题）\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due").value("2026-09-25"));

        // due 传空串 = 清除到期日
        mvc.perform(put("/api/v1/todos/" + todo.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"due\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updateTodo_badStatus_400HumanReadable() throws Exception {
        Todo todo = seed("default", "买菜", null);
        mvc.perform(put("/api/v1/todos/" + todo.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("待办状态只有「未完成 / 已完成」两种"));
    }

    @Test
    void updateTodo_notFound_400HumanReadable() throws Exception {
        mvc.perform(put("/api/v1/todos/todo_none")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("没找到这条待办"));
    }

    @Test
    void deleteTodo_returns204() throws Exception {
        Todo todo = seed("default", "删掉我", null);

        mvc.perform(delete("/api/v1/todos/" + todo.id()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/todos"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getStats_returnsTwoStateCounts() throws Exception {
        Todo a = seed("default", "a", null);
        seed("default", "b", null);
        todoService.updateTodo("default", a.id(), null,
                com.adaiadai.core.kernel.todo.TodoStatus.DONE, null, false);

        mvc.perform(get("/api/v1/todos/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.done").value(1));
    }

    @Test
    void listTodos_filterByStatus() throws Exception {
        Todo a = seed("default", "未完成", null);
        Todo b = seed("default", "已完成", null);
        todoService.updateTodo("default", b.id(), null,
                com.adaiadai.core.kernel.todo.TodoStatus.DONE, null, false);

        mvc.perform(get("/api/v1/todos").param("status", "OPEN"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(a.id()));
    }

    @Test
    void listTodos_badStatus_400HumanReadable() throws Exception {
        mvc.perform(get("/api/v1/todos").param("status", "DOING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("待办状态只有「未完成 / 已完成」两种"));
    }
}
