package com.railwayteam.railways.base.data.lang;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.railwayteam.railways.Railways;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.ponder.PonderScene;
import com.simibubi.create.foundation.utility.FilesHelper;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.HashCache;
import net.minecraft.util.GsonHelper;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;

public class LangMerger implements DataProvider {

	private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting()
		.disableHtmlEscaping()
		.create();
	static final String CATEGORY_HEADER = "\t\"_\": \"->------------------------]  %s  [------------------------<-\",";

	private DataGenerator gen;

	private List<Object> mergedLangData;
	private Map<String, List<Object>> populatedLangData;
	private Map<String, Map<String, String>> allLocalizedEntries;
	private Map<String, MutableInt> missingTranslationTally;

	private List<String> langIgnore;

	public LangMerger(DataGenerator gen) {
		this.gen = gen;
		this.mergedLangData = new ArrayList<>();
		this.langIgnore = new ArrayList<>();
		this.allLocalizedEntries = new HashMap<>();
		this.populatedLangData = new HashMap<>();
		this.missingTranslationTally = new HashMap<>();
		populateLangIgnore();
	}

	private void populateLangIgnore() {
		// Key prefixes added here will NOT be transferred to lang templates
		langIgnore.add("create.ponder.debug_"); // Ponder debug scene text
		langIgnore.add("create.gui.chromatic_projector"); 
	}

	private boolean shouldIgnore(String key) {
		for (String string : langIgnore)
			if (key.startsWith(string))
				return true;
		return false;
	}

	@Override
	public String getName() {
		return "Lang merger";
	}

	@Override
	public void run(@NotNull CachedOutput cache) throws IOException {
		Path path = this.gen.getOutputFolder()
			.resolve("assets/" + Railways.MODID + "/lang/" + "en_us.json");

		for (Pair<String, JsonElement> pair : getAllLocalizationFiles()) {
			if (!pair.getRight()
				.isJsonObject())
				continue;
			Map<String, String> localizedEntries = new HashMap<>();
			JsonObject jsonobject = pair.getRight()
				.getAsJsonObject();
			jsonobject.entrySet()
				.stream()
				.forEachOrdered(entry -> {
					String key = entry.getKey();
					if (key.startsWith("_"))
						return;
					String value = entry.getValue()
						.getAsString();
					localizedEntries.put(key, value);
				});
			String key = pair.getKey();
			allLocalizedEntries.put(key, localizedEntries);
			populatedLangData.put(key, new ArrayList<>());
			missingTranslationTally.put(key, new MutableInt(0));
		}

		collectExistingEntries(path);
		collectEntries();
		if (mergedLangData.isEmpty())
			return;

		save(cache, mergedLangData, -1, path, "Merging en_us.json with hand-written lang entries...");
		for (Entry<String, List<Object>> localization : populatedLangData.entrySet()) {
			String key = localization.getKey();
			Path populatedLangPath = this.gen.getOutputFolder()
				.resolve("assets/" + Railways.MODID + "/lang/unfinished/" + key);
			save(cache, localization.getValue(), missingTranslationTally.get(key)
				.intValue(), populatedLangPath, "Populating " + key + " with missing entries...");
		}
	}

	private void collectExistingEntries(Path path) throws IOException {
		if (!Files.exists(path)) {
			Railways.LOGGER.warn("Nothing to merge! It appears no lang was generated before me.");
			return;
		}

		try (BufferedReader reader = Files.newBufferedReader(path)) {
			JsonObject jsonobject = GsonHelper.fromJson(GSON, reader, JsonObject.class);
			addAll("Game Elements", jsonobject);
			reader.close();
		}
	}

	protected void addAll(String header, JsonObject jsonobject) {
		if (jsonobject == null)
			return;
		header = String.format(CATEGORY_HEADER, header);

		writeData("\n");
		writeData(header);
		writeData("\n\n");

		MutableObject<String> previousKey = new MutableObject<>("");
		jsonobject.entrySet()
			.stream()
			.forEachOrdered(entry -> {
				String key = entry.getKey();
				if (shouldIgnore(key))
					return;
				String value = entry.getValue()
					.getAsString();
				if (!previousKey.getValue()
					.isEmpty() && shouldAddLineBreak(key, previousKey.getValue()))
					writeData("\n");
				writeEntry(key, value);
				previousKey.setValue(key);
			});

		writeData("\n");
	}

	private void writeData(String data) {
		mergedLangData.add(data);
		populatedLangData.values()
			.forEach(l -> l.add(data));
	}

	private void writeEntry(String key, String value) {
		mergedLangData.add(new LangEntry(key, value));
		populatedLangData.forEach((k, l) -> {
			ForeignLangEntry entry = new ForeignLangEntry(key, value, allLocalizedEntries.get(k));
			if (entry.isMissing())
				missingTranslationTally.get(k)
					.increment();
			l.add(entry);
		});
	}

	protected boolean shouldAddLineBreak(String key, String previousKey) {
		// Always put tooltips and ponder scenes in their own paragraphs
		if (key.endsWith(".tooltip"))
			return true;
		if (key.startsWith("create.ponder") && key.endsWith(PonderScene.TITLE_KEY))
			return true;

		key = key.replaceFirst("\\.", "");
		previousKey = previousKey.replaceFirst("\\.", "");

		String[] split = key.split("\\.");
		String[] split2 = previousKey.split("\\.");
		if (split.length == 0 || split2.length == 0)
			return false;

		// Start new paragraph if keys before second point do not match
		return !split[0].equals(split2[0]);
	}

	private List<Pair<String, JsonElement>> getAllLocalizationFiles() {
		ArrayList<Pair<String, JsonElement>> list = new ArrayList<>();

		String filepath = "assets/" + Railways.MODID + "/lang/";
		try (InputStream resourceStream = ClassLoader.getSystemResourceAsStream(filepath)) {
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(resourceStream));
			while (true) {
				String readLine = bufferedReader.readLine();
				if (readLine == null)
					break;
				if (!readLine.endsWith(".json"))
					continue;
				if (readLine.startsWith("en_us") || readLine.startsWith("en_ud"))
					continue;
				list.add(Pair.of(readLine, FilesHelper.loadJsonResource(filepath + readLine)));
			}
		} catch (IOException | NullPointerException e) {
			e.printStackTrace();
		}

		return list;
	}

	private void collectEntries() {
		for (CRLangPartials partial : CRLangPartials.values())
			addAll(partial.getDisplay(), partial.provide()
				.getAsJsonObject());
	}

	@SuppressWarnings("deprecation")
	private void save(CachedOutput cache, List<Object> dataIn, int missingKeys, Path target, String message)
		throws IOException {
		Create.LOGGER.info(message);

		ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();
		HashingOutputStream hashingoutputstream = new HashingOutputStream(Hashing.sha1(), bytearrayoutputstream);

		Writer writer = new OutputStreamWriter(hashingoutputstream, StandardCharsets.UTF_8);
		writer.append(createString(dataIn, missingKeys));
		writer.close();

		cache.writeIfNeeded(target, bytearrayoutputstream.toByteArray(), hashingoutputstream.hash());
	}

	protected String createString(List<Object> data, int missingKeys) {
		StringBuilder builder = new StringBuilder();
		builder.append("{\n");
		if (missingKeys != -1)
			builder.append("\t\"_\": \"Missing Localizations: " + missingKeys + "\",\n");
		data.forEach(builder::append);
		builder.append("\t\"_\": \"Thank you for translating Create Steam 'n Rails!\"\n\n");
		builder.append("}");
		return builder.toString();
	}

	private class LangEntry {
		static final String ENTRY_FORMAT = "\t\"%s\": %s,\n";

		private String key;
		private String value;

		LangEntry(String key, String value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public String toString() {
			return String.format(ENTRY_FORMAT, key, GSON.toJson(value, String.class));
		}

	}

	private class ForeignLangEntry extends LangEntry {

		private boolean missing;

		ForeignLangEntry(String key, String value, Map<String, String> localizationMap) {
			super(key, localizationMap.getOrDefault(key, "UNLOCALIZED: " + value));
			missing = !localizationMap.containsKey(key);
		}

		public boolean isMissing() {
			return missing;
		}

	}

}
