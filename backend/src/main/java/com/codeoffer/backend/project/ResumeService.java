package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 简历域服务:STAR 编辑;重生成委托 GenerateService(已编辑字段不被覆盖)。 */
@Service
public class ResumeService {

    private final ResumeEntryRepository resumes;
    private final ProjectService projectService;
    private final GenerateService generate;

    public ResumeService(
            ResumeEntryRepository resumes,
            ProjectService projectService,
            GenerateService generate) {
        this.resumes = resumes;
        this.projectService = projectService;
        this.generate = generate;
    }

    public ResumeView editStar(Long userId, Long id, String starContent) {
        projectService.requireProject(userId, id);
        ResumeEntry entry = resumes.findByProjectId(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "尚无简历产出"));
        if (starContent == null || starContent.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        entry.setStarContent(starContent);
        entry.setEdited(true);
        entry.setUpdatedAt(Instant.now());
        return ResumeView.from(resumes.save(entry));
    }

    public ResumeView regenerate(Long userId, Long id) {
        projectService.requireProject(userId, id);
        return ResumeView.from(generate.regenerateResume(id));
    }
}