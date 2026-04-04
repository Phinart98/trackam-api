package com.trackam.service;

import com.trackam.dto.GoalRequest;
import com.trackam.model.Goal;
import com.trackam.repository.GoalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository repo;

    public List<Goal> getAll(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public Goal create(GoalRequest req, UUID userId) {
        Goal goal = Goal.builder()
            .userId(userId)
            .name(req.name())
            .targetAmount(req.targetAmount())
            .currentAmount(req.currentAmount())
            .currency(req.currency())
            .icon(req.icon())
            .color(req.color())
            .bgColor(req.bgColor())
            .dotColor(req.dotColor())
            .deadline(req.deadline() != null && !req.deadline().isBlank()
                ? LocalDate.parse(req.deadline()) : null)
            .build();
        return repo.save(goal);
    }

    public Goal update(UUID id, GoalRequest req, UUID userId) {
        Goal goal = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));

        goal.setName(req.name());
        goal.setTargetAmount(req.targetAmount());
        goal.setCurrentAmount(req.currentAmount());
        goal.setCurrency(req.currency());
        goal.setIcon(req.icon());
        goal.setColor(req.color());
        goal.setBgColor(req.bgColor());
        goal.setDotColor(req.dotColor());
        goal.setDeadline(req.deadline() != null && !req.deadline().isBlank()
            ? LocalDate.parse(req.deadline()) : null);

        return repo.save(goal);
    }

    public void delete(UUID id, UUID userId) {
        Goal goal = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
        repo.delete(goal);
    }
}
