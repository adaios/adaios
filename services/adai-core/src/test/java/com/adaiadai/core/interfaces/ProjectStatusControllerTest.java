package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ProjectStatusAppService;
import com.adaiadai.core.application.ProjectTaskAppService;
import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProjectStatusController — 项目状态 + 任务 CRUD + 统计接口测试。
 */
class ProjectStatusControllerTest {

    private MockMvc buildMvc(ProjectStatusAppService statusService,
                             ProjectTaskAppService taskService) {
        ProjectStatusController controller = new ProjectStatusController(statusService, taskService);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private Task task(String id) {
        return new Task(id, "任务标题", "任务描述", TaskStatus.TODO,
                "P2", List.of("后端"), "20260728-project-development-suggestions",
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 2));
    }

    @Test
    void getStatus_returnsResult() throws Exception {
        ProjectStatusAppService statusService = mock(ProjectStatusAppService.class);
        ProjectStatusAppService.StatusResult result = new ProjectStatusAppService.StatusResult(
                "AdaiOS", "modular monolith",
                Map.of("context", "ready", "memory", "ready"),
                Map.of("trading", "active", "project", "active"),
                List.of(new ProjectStatusAppService.RfcItem("adai-admin 规划", "2026-08-02", "approved")),
                128, 44);
        when(statusService.getStatus()).thenReturn(result);
        MockMvc mvc = buildMvc(statusService, mock(ProjectTaskAppService.class));

        mvc.perform(get("/api/v1/project/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project").value("AdaiOS"))
                .andExpect(jsonPath("$.architecture").value("modular monolith"))
                .andExpect(jsonPath("$.commitCount").value(128))
                .andExpect(jsonPath("$.apiEndpoints").value(44))
                .andExpect(jsonPath("$.rfcItems[0].status").value("approved"));
    }

    @Test
    void listTasks_returnsList() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        when(taskService.listTasks(any(), any(), any())).thenReturn(List.of(task("task_1")));
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(get("/api/v1/project/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("task_1"))
                .andExpect(jsonPath("$[0].status").value("TODO"));
    }

    @Test
    void listTasks_forwardsFiltersAndUserId() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        when(taskService.listTasks(any(), any(), any())).thenReturn(List.of());
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(get("/api/v1/project/tasks")
                        .header("X-User-Id", "alice")
                        .param("status", "DONE")
                        .param("tag", "后端"))
                .andExpect(status().isOk());
        verify(taskService).listTasks("alice", TaskStatus.DONE, "后端");
    }

    @Test
    void createTask_returnsTask() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        when(taskService.createTask(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task("task_new"));
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(post("/api/v1/project/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"新任务","description":"描述","priority":"P1","tags":["前端"],"rfcRef":"20260802-adai-admin"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task_new"))
                .andExpect(jsonPath("$.title").value("任务标题"));
    }

    @Test
    void updateTask_returnsTask() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        when(taskService.updateTask(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task("task_1"));
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(put("/api/v1/project/tasks/task_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"改标题","description":"","priority":"P2","tags":[],"rfcRef":null,"status":"DOING"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task_1"));
    }

    @Test
    void deleteTask_returns204() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(delete("/api/v1/project/tasks/task_1")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isNoContent());
        verify(taskService).deleteTask("alice", "task_1");
    }

    @Test
    void getTaskStats_returnsStats() throws Exception {
        ProjectTaskAppService taskService = mock(ProjectTaskAppService.class);
        when(taskService.getStats(any())).thenReturn(
                new TaskRepository.TaskStats(5, 1, 1, 2, 1));
        MockMvc mvc = buildMvc(mock(ProjectStatusAppService.class), taskService);

        mvc.perform(get("/api/v1/project/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.todo").value(1))
                .andExpect(jsonPath("$.doing").value(1))
                .andExpect(jsonPath("$.done").value(2))
                .andExpect(jsonPath("$.cancelled").value(1));
    }
}
