package com.bedrock.app.action.application;

import com.bedrock.app.action.block.BlockCatalog;
import com.bedrock.app.action.domain.Action;
import com.bedrock.app.action.domain.Action.BlockAssignment;
import com.bedrock.app.action.domain.ActionBlock;
import com.bedrock.app.action.domain.ActionRepository;
import com.bedrock.app.action.domain.type.BlockCode;
import com.bedrock.app.action.dto.ActionRequests;
import com.bedrock.app.action.dto.ActionResponse;
import com.bedrock.app.item.dto.response.ItemResponse;
import com.bedrock.app.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActionService {

    private final ActionRepository actionRepository;
    private final BlockCatalog blockCatalog;
    private final ItemService itemService;

    @Transactional
    public ActionResponse create(
            Long ownerId,
            boolean admin,
            ActionRequests.Create request
    ) {
        boolean preset = Boolean.TRUE.equals(request.preset());
        requirePresetPermission(admin, preset);

        List<BlockAssignment> blocks =
                validateAndOrderBlocks(request.blocks());
        Action action = Action.builder()
                .ownerId(ownerId)
                .name(normalizeRequiredName(request.name()))
                .description(normalizeDescription(request.description()))
                .enabled(request.enabled() == null || request.enabled())
                .preset(preset)
                .blockAssignments(blocks)
                .build();

        Action saved = actionRepository.save(action);
        return ActionResponse.from(saved, blockCatalog);
    }

    @Transactional
    public ActionResponse update(
            UUID actionId,
            Long ownerId,
            boolean admin,
            ActionRequests.Update request
    ) {
        Action action = findVisibleActionOrThrow(actionId, ownerId);
        requireModificationPermission(ownerId, admin, action);

        String updatedName = request.name() == null
                ? action.getName()
                : normalizeRequiredName(request.name());
        String updatedDescription = request.description() == null
                ? action.getDescription()
                : normalizeDescription(request.description());
        boolean updatedEnabled = request.enabled() == null
                ? action.isEnabled()
                : request.enabled();
        boolean updatedPreset = request.preset() == null
                ? action.isPreset()
                : request.preset();
        List<BlockAssignment> updatedBlocks = request.blocks() == null
                ? null
                : validateAndOrderBlocks(request.blocks());

        requirePresetPermission(admin, updatedPreset);
        action.updateDefinition(
                updatedName,
                updatedDescription,
                updatedEnabled,
                updatedPreset,
                updatedBlocks
        );

        return ActionResponse.from(action, blockCatalog);
    }

    @Transactional
    public void delete(UUID actionId, Long ownerId, boolean admin) {
        Action action = findVisibleActionOrThrow(actionId, ownerId);
        requireModificationPermission(ownerId, admin, action);
        action.delete();
    }

    @Transactional(readOnly = true)
    public List<ActionResponse> findAll(
            Long ownerId,
            String scope
    ) {
        String normalizedScope = normalizeScope(scope);
        List<ActionResponse> responses = new ArrayList<>();
        for (Action action : actionRepository.findAllVisible(ownerId)) {
            if (matchesScope(action, ownerId, normalizedScope)) {
                responses.add(ActionResponse.from(action, blockCatalog));
            }
        }
        return List.copyOf(responses);
    }

    @Transactional(readOnly = true)
    public ActionResponse findOne(UUID actionId, Long ownerId) {
        Action action = findVisibleActionOrThrow(actionId, ownerId);
        return ActionResponse.from(action, blockCatalog);
    }

    @Transactional
    public ActionResponse.Execution execute(
            UUID actionId,
            Long ownerId,
            ActionRequests.Execute request
    ) {
        Action action = findExecutableAction(actionId, ownerId);
        if (request.targetCollectionId() == null) {
            throw badRequest("targetCollectionId is required");
        }

        List<BlockCode> blockCodes = getOrderedBlockCodes(action);
        Map<String, Object> attributes = resolveItemAttributes(
                blockCodes,
                request.triggerPayload(),
                request.input()
        );

        ItemResponse item = itemService.createInCollection(
                ownerId,
                request.targetCollectionId(),
                toItemBlockCodeNames(blockCodes),
                attributes
        );
        return new ActionResponse.Execution(
                actionId,
                item.id(),
                ActionResponse.Execution.Status.COMPLETED,
                item
        );
    }

    private String normalizeScope(String scope) {
        String normalizedScope = scope == null
                ? "available"
                : scope.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("available", "me", "preset").contains(normalizedScope)) {
            throw badRequest("scope must be available, me, or preset");
        }
        return normalizedScope;
    }

    private boolean matchesScope(
            Action action,
            Long ownerId,
            String scope
    ) {
        if ("available".equals(scope)) {
            return true;
        }
        if ("me".equals(scope)) {
            return action.getOwnerId().equals(ownerId)
                    && !action.isPreset();
        }
        if ("preset".equals(scope)) {
            return action.isPreset();
        }
        return false;
    }

    private Action findExecutableAction(UUID actionId, Long ownerId) {
        Action action = findVisibleActionOrThrow(actionId, ownerId);
        if (!action.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Disabled actions cannot be executed"
            );
        }
        return action;
    }

    private List<BlockCode> getOrderedBlockCodes(Action action) {
        List<ActionBlock> orderedBlocks =
                new ArrayList<>(action.getBlocks());
        orderedBlocks.sort(
                Comparator.comparingInt(ActionBlock::getDisplayOrder)
        );

        List<BlockCode> blockCodes = new ArrayList<>();
        for (ActionBlock block : orderedBlocks) {
            blockCodes.add(block.getBlockCode());
        }
        return List.copyOf(blockCodes);
    }

    private Set<String> toItemBlockCodeNames(List<BlockCode> blockCodes) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (BlockCode blockCode : blockCodes) {
            names.add(blockCode.name());
        }
        return names;
    }

    private Map<String, Object> resolveItemAttributes(
            List<BlockCode> blockCodes,
            Map<String, Object> triggerPayload,
            Map<String, Object> input
    ) {
        LinkedHashMap<String, BlockCatalog.Field> fieldsByKey =
                indexFieldsByKey(blockCodes);

        Map<String, Object> safeTriggerPayload =
                triggerPayload == null ? Map.of() : triggerPayload;
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        rejectUnknownFields(
                fieldsByKey.keySet(),
                safeTriggerPayload,
                "triggerPayload"
        );
        rejectUnknownFields(fieldsByKey.keySet(), safeInput, "input");

        LinkedHashMap<String, Object> attributes = mergeInputValues(
                fieldsByKey.values(),
                safeTriggerPayload,
                safeInput
        );
        for (BlockCatalog.Field field : fieldsByKey.values()) {
            validateAndRemoveEmptyField(field, attributes);
        }
        return Map.copyOf(attributes);
    }

    private LinkedHashMap<String, BlockCatalog.Field> indexFieldsByKey(
            List<BlockCode> blockCodes
    ) {
        LinkedHashMap<String, BlockCatalog.Field> fieldsByKey =
                new LinkedHashMap<>();
        for (BlockCatalog.MergedField merged
                : blockCatalog.mergeFields(blockCodes)) {
            fieldsByKey.put(merged.field().key(), merged.field());
        }
        return fieldsByKey;
    }

    private LinkedHashMap<String, Object> mergeInputValues(
            Collection<BlockCatalog.Field> fields,
            Map<String, Object> triggerPayload,
            Map<String, Object> input
    ) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (BlockCatalog.Field field : fields) {
            if (field.defaultValue() != null) {
                values.put(field.key(), field.defaultValue());
            }
        }
        values.putAll(triggerPayload);
        values.putAll(input);
        return values;
    }

    private void rejectUnknownFields(
            Set<String> allowedFields,
            Map<String, Object> values,
            String source
    ) {
        for (String key : values.keySet()) {
            if (!allowedFields.contains(key)) {
                throw badRequest("Unknown " + source + " field: " + key);
            }
        }
    }

    private void validateAndRemoveEmptyField(
            BlockCatalog.Field field,
            Map<String, Object> values
    ) {
        Object value = values.get(field.key());
        boolean empty = value == null;
        if (value instanceof String) {
            empty = ((String) value).isBlank();
        }

        if (empty) {
            if (field.required()) {
                throw badRequest(field.key() + " is required");
            }
            values.remove(field.key());
            return;
        }
        if (!matchesFieldDefinition(field, value)) {
            throw badRequest("Invalid value for " + field.key());
        }
    }

    private boolean matchesFieldDefinition(
            BlockCatalog.Field field,
            Object value
    ) {
        switch (field.type()) {
            case TEXT:
            case MARKDOWN:
                return value instanceof String;
            case DATE:
                return isValidDate(value);
            case TIME:
                return isValidTime(value);
            case BOOLEAN:
                return value instanceof Boolean;
            case STRING_LIST:
                return isStringList(value);
            case INTEGER:
                return isWholeNumber(value);
            case ENUM:
                return value instanceof String
                        && field.options().contains(value);
            default:
                return false;
        }
    }

    private boolean isValidDate(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        try {
            LocalDate.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean isValidTime(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        try {
            LocalTime.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean isStringList(Object value) {
        if (!(value instanceof Collection<?>)) {
            return false;
        }
        for (Object element : (Collection<?>) value) {
            if (!(element instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWholeNumber(Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double number = ((Number) value).doubleValue();
        return number == Math.rint(number);
    }

    private List<BlockAssignment> validateAndOrderBlocks(
            List<ActionRequests.Block> requests
    ) {
        if (requests == null || requests.isEmpty()) {
            throw invalidBlock("reason", "at least one block is required");
        }

        EnumSet<BlockCode> seenBlocks = EnumSet.noneOf(BlockCode.class);
        Set<Integer> usedOrders = new HashSet<>();
        List<BlockAssignment> assignments = new ArrayList<>();
        int nextOrder = 0;

        for (ActionRequests.Block request : requests) {
            if (request == null || request.blockCode() == null) {
                throw invalidBlock("reason", "blockCode is required");
            }
            if (!seenBlocks.add(request.blockCode())) {
                throw invalidBlock("duplicateBlockCode", request.blockCode().name());
            }

            Integer requestedOrder = request.displayOrder();
            if (requestedOrder != null && requestedOrder < 0) {
                throw invalidBlock("displayOrder", requestedOrder);
            }

            int displayOrder;
            if (requestedOrder == null) {
                while (usedOrders.contains(nextOrder)) {
                    nextOrder++;
                }
                displayOrder = nextOrder++;
            } else {
                displayOrder = requestedOrder;
            }

            if (!usedOrders.add(displayOrder)) {
                throw invalidBlock("duplicateDisplayOrder", displayOrder);
            }
            assignments.add(new BlockAssignment(request.blockCode(), displayOrder));
        }

        assignments.sort(Comparator.comparingInt(BlockAssignment::displayOrder));
        validateBlockCombination(assignments);
        return List.copyOf(assignments);
    }

    private void validateBlockCombination(
            List<BlockAssignment> assignments
    ) {
        List<BlockCode> blockCodes = new ArrayList<>();
        for (BlockAssignment assignment : assignments) {
            blockCodes.add(assignment.blockCode());
        }
        blockCatalog.mergeFields(blockCodes);
    }

    private Action findVisibleActionOrThrow(UUID actionId, Long ownerId) {
        return actionRepository.findVisibleById(actionId, ownerId)
                .orElseThrow(() -> notFound("Action not found: " + actionId));
    }

    private void requireModificationPermission(
            Long ownerId,
            boolean admin,
            Action action
    ) {
        if (action.isPreset()) {
            if (!admin) {
                throw forbidden("Only admins can edit preset actions");
            }
            return;
        }

        if (!action.getOwnerId().equals(ownerId)) {
            throw forbidden("You cannot edit this action");
        }
    }

    private void requirePresetPermission(boolean admin, boolean preset) {
        if (preset && !admin) {
            throw forbidden("Only admins can create preset actions");
        }
    }

    private ResponseStatusException invalidBlock(String key, Object value) {
        return badRequest("Invalid block: " + key + "=" + value);
    }

    private String normalizeRequiredName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw badRequest("name is required");
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

}
