package me.whereareiam.configura.common.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.common.merge.defaults.DefaultsProviderRegistry;
import me.whereareiam.configura.common.merge.defaults.ModelDefaults;
import me.whereareiam.configura.common.merge.track.TrackedDefaults;
import me.whereareiam.configura.document.DocumentProcessor;
import me.whereareiam.configura.merge.MergeBehavior;
import me.whereareiam.configura.type.PrimitiveDefaultPolicy;
import me.whereareiam.configura.type.UnknownFieldPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Produces the document that is bound and written: what the user wrote, completed by the defaults
 * of the model.
 */
public final class MergeEngine {
	private final ObjectMapper mapper;
	private final DocumentProcessor documents;
	private final ModelDefaults modelDefaults;
	private final MergeBehavior behavior;

	public MergeEngine(
			@NotNull ObjectMapper mapper,
			@NotNull DefaultsProviderRegistry providers,
			@NotNull DocumentProcessor documents,
			@NotNull MergeBehavior behavior
	) {
		this.mapper = mapper;
		this.documents = documents;
		this.modelDefaults = new ModelDefaults(mapper, providers, documents);
		this.behavior = behavior;
	}

	/**
	 * Completes a document with the defaults of a freshly constructed model, as {@code update} does.
	 *
	 * @param source  what the file has, if anything
	 * @param model   freshly constructed model
	 * @param type    model type
	 * @param tracked defaults the previous write put into the file, which then follow the current
	 *                defaults where nobody changed them and record them for the next write; null
	 *                when the instance does not track defaults
	 * @return merged document
	 */
	public <T> @NotNull ObjectNode mergeDefaults(
			@Nullable JsonNode source,
			@NotNull T model,
			@NotNull Class<T> type,
			@Nullable TrackedDefaults tracked
	) {
		return merge(source, model, type, behavior.getPrimitiveDefaultPolicy(), tracked);
	}

	/**
	 * Completes a document with a model the caller filled in, whose zeros and falses are therefore
	 * meant, as {@code save} and {@code merge} do.
	 *
	 * @param source what the file or the model has
	 * @param model  model whose values serve as defaults
	 * @param type   model type
	 * @return merged document
	 */
	public <T> @NotNull ObjectNode mergeUserModel(@Nullable JsonNode source, @NotNull T model, @NotNull Class<T> type) {
		return merge(source, model, type, PrimitiveDefaultPolicy.PRESERVE, null);
	}

	private ObjectNode merge(
			@Nullable JsonNode source,
			Object model,
			Class<?> type,
			PrimitiveDefaultPolicy policy,
			@Nullable TrackedDefaults tracked
	) {
		boolean keepsUnknown = behavior.getUnknownFieldPolicy() == UnknownFieldPolicy.PRESERVE || TreeMerge.keepsUnknown(type);

		return new TreeMerge(mapper, documents, modelDefaults, policy, tracked)
				.object(source, modelDefaults.of(model, type, policy), type, keepsUnknown, tracked == null ? null : "");
	}
}
