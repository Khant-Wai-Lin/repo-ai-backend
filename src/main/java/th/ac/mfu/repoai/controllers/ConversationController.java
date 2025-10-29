// src/main/java/th/ac/mfu/repoai/controllers/ConversationController.java
package th.ac.mfu.repoai.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import th.ac.mfu.repoai.domain.Conversation;
import th.ac.mfu.repoai.domain.ConversationStatus;
import th.ac.mfu.repoai.repository.ConversationRepository;
import th.ac.mfu.repoai.repository.UserRepository;
import th.ac.mfu.repoai.services.ConversationService;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService service;
    private final ConversationRepository convos;
    private final UserRepository users;

    public ConversationController(
            ConversationService service,
            ConversationRepository convos,
            UserRepository users) {
        this.service = service;
        this.convos = convos;
        this.users = users;
    }

    // -----------------------------------------
    // 1) Create a conversation
    // -----------------------------------------
    @PostMapping
    public ResponseEntity<ConversationDTO> create(
            Authentication auth,
            @Valid @RequestBody CreateConversationRequest req) {

        Long githubId = resolveGithubId(auth, req.githubId());
        var saved = service.create(
                githubId,
                req.repoId(),
                req.branchId(), // may be null
                req.title(),
                req.goal(),
                req.metadataJson());

        return ResponseEntity.ok(ConversationDTO.from(saved));
    }

    // -----------------------------------------
    // 2) List my conversations (optionally filter by repo or status)
    // -----------------------------------------
    @GetMapping
    public ResponseEntity<List<ConversationDTO>> listMine(
            Authentication auth,
            @RequestParam(required = false) Long githubId,
            @RequestParam(required = false) Long repoId,
            @RequestParam(required = false) ConversationStatus status) {

        Long currentGithubId = resolveGithubId(auth, githubId);

        List<Conversation> list;
        if (repoId != null && status != null) {
            list = convos.findByUserGithubIdAndRepositoryRepoIdAndStatusOrderByUpdatedAtDesc(currentGithubId, repoId,
                    status);
        } else if (repoId != null) {
            list = convos.findByUserGithubIdAndRepositoryRepoIdOrderByUpdatedAtDesc(currentGithubId, repoId);
        } else if (status != null) {
            list = convos.findByUserGithubIdAndStatusOrderByUpdatedAtDesc(currentGithubId, status);
        } else {
            list = convos.findByUserGithubIdOrderByUpdatedAtDesc(currentGithubId);
        }

        return ResponseEntity.ok(list.stream().map(ConversationDTO::from).toList());
    }

    // -----------------------------------------
    // 3) Get one conversation by id (owner-only)
    // -----------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<ConversationDTO> getOne(
            Authentication auth,
            @PathVariable Long id,
            @RequestParam(required = false) Long githubId) {

        Long currentGithubId = resolveGithubId(auth, githubId);
        var convo = convos.findById(id).orElse(null);
        if (convo == null || convo.getUser() == null ||
                !currentGithubId.equals(convo.getUser().getGithubId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ConversationDTO.from(convo));
    }

    // -----------------------------------------
    // 4) Archive (soft-delete) a conversation
    // -----------------------------------------
    @PostMapping("/{id}/archive")
    public ResponseEntity<ConversationDTO> archive(
            Authentication auth,
            @PathVariable Long id,
            @RequestParam(required = false) Long githubId) {

        Long currentGithubId = resolveGithubId(auth, githubId);
        var updated = service.archive(id, currentGithubId);
        return ResponseEntity.ok(ConversationDTO.from(updated));
    }

    // === Helpers ===

    /**
     * Determine the current user's GitHub id.
     * Priority:
     * 1) Explicit githubId (query/body)
     * 2) Numeric principal name from OAuth (common with GitHub)
     * 3) Otherwise, fail with a clear message
     */
    private Long resolveGithubId(Authentication auth, Long githubIdFromClient) {
        if (githubIdFromClient != null)
            return githubIdFromClient;

        if (auth != null && auth.getName() != null) {
            String name = auth.getName();
            if (name.matches("\\d+")) {
                users.findByGithubId(Long.parseLong(name))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No local user row for GitHub id " + name + ". Call /api/auth/login first."));
                return Long.parseLong(name);
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve githubId. Provide ?githubId=... or ensure OAuth principal name is the numeric GitHub id.");
    }

    // ===== DTOs =====

    public record CreateConversationRequest(
            @NotNull Long repoId,
            Long branchId,
            @NotBlank String title,
            @NotBlank String goal,
            String metadataJson,
            Long githubId) {
    }

    public record ConversationDTO(
            Long id,
            Long userGithubId,
            Long repoId,
            Long branchId,
            String title,
            String goal,
            ConversationStatus status,
            String metadataJson,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime lastMessageAt) {
        public static ConversationDTO from(Conversation c) {
            return new ConversationDTO(
                    c.getId(),
                    c.getUser() != null ? c.getUser().getGithubId() : null,
                    c.getRepository() != null ? c.getRepository().getRepoId() : null,
                    c.getBranch() != null ? c.getBranch().getId() : null,
                    c.getTitle(),
                    c.getGoal(),
                    c.getStatus(),
                    c.getMetadataJson(),
                    c.getCreatedAt(),
                    c.getUpdatedAt(),
                    c.getLastMessageAt());
        }
    }

}
