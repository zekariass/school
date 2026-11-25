package com.nemaz.school.imports.domain;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.nemaz.school.common.text.PersonNames;

/**
 * Matches the header cells of a file to field keys, so that most files map themselves. Matching ignores case,
 * spaces, underscores, hyphens, dots and other punctuation, and the Ethiopic homophones, so {@code Date of Birth},
 * {@code date_of_birth} and {@code DOB} meet the same field, and so do {@code ፆታ} and {@code ጾታ}. Each column
 * and each field is used once; the first matching column wins. Amharic headers are drafts pending review by a
 * native speaker, like every Amharic text of the platform.
 */
public final class HeaderSynonyms {

	/** Synonyms by field key, shared by every import type that has the field (a type only looks at its own fields). */
	private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

	static {
		put("given_name", "first name", "firstname", "given name", "name", "student name", "staff name", "forename",
				"የመጀመሪያ ስም", "ስም", "የተማሪ ስም", "የሠራተኛ ስም", "የመምህር ስም", "የራስ ስም");
		put("father_name", "father name", "fathers name", "father", "middle name", "middlename", "second name",
				"የአባት ስም", "አባት", "የአባት");
		put("grandfather_name", "grandfather name", "grandfathers name", "grandfather", "last name", "lastname",
				"surname", "family name", "familyname", "third name", "የአያት ስም", "አያት", "የአያት", "የአያት ስም");
		put("date_of_birth", "dob", "birth date", "birthdate", "date of birth", "birthday", "born", "የትውልድ ቀን",
				"የተወለደበት ቀን", "ትውልድ", "የልደት ቀን");
		put("gender", "sex", "ፆታ", "ጾታ");
		put("student_number", "student id", "studentid", "student no", "student number", "id number", "admission number",
				"admission no", "reg no", "registration number", "roll number", "የተማሪ ቁጥር", "የተማሪ መታወቂያ", "መታወቂያ ቁጥር");
		put("external_id", "external id", "externalid", "sis id", "ext id", "employee id", "employee number", "staff id",
				"ref", "reference", "የውጭ መታወቂያ", "የሠራተኛ መታወቂያ");
		put("class", "class name", "classname", "section", "group", "homeroom", "room", "ክፍል", "የክፍል ስም", "ክፍል ስም");
		put("class_name", "class", "class name", "classname", "section", "homeroom", "ክፍል", "የክፍል ስም", "ክፍል ስም");
		put("grade_level", "grade", "grade level", "level", "gradelevel", "ክፍል ደረጃ", "ደረጃ", "የክፍል ደረጃ");
		put("year", "academic year", "school year", "yr", "የትምህርት ዘመን", "ዓ.ም", "ዓመት");
		put("campus", "branch", "site", "ቅርንጫፍ", "ካምፓስ");
		put("class_type", "type", "class type", "ክፍል ዓይነት", "የክፍል ዓይነት");
		put("username", "user name", "user", "login", "የተጠቃሚ ስም");
		put("phone", "phone number", "mobile", "mobile number", "cell", "telephone", "tel", "phone no", "ስልክ",
				"ስልክ ቁጥር", "ሞባይል");
		put("email", "e-mail", "email address", "mail", "ኢሜይል", "ኢሜል", "ኢሜይል አድራሻ");
		put("staff_type", "role", "staff role", "position type", "type of staff", "የሠራተኛ ዓይነት", "ሚና");
		put("job_title", "title", "job", "position", "designation", "subject taught", "የሥራ መደብ", "የስራ መደብ", "የሥራ ድርሻ");
		put("lead_teacher_phone", "teacher phone", "lead teacher phone", "homeroom teacher phone", "የመምህር ስልክ");
		put("lead_teacher_email", "teacher email", "lead teacher email", "homeroom teacher email", "የመምህር ኢሜይል");
		put("name", "subject", "subject name", "grade name", "grade level name", "title", "የትምህርት ዓይነት", "ስም",
				"የትምህርት ስም", "ደረጃ");
		put("code", "subject code", "short code", "abbreviation", "ኮድ", "ምህጻረ ቃል");
		put("sequence", "order", "sort order", "position", "number", "seq", "ተራ ቁጥር", "ቅደም ተከተል");
		put("color", "colour", "ቀለም");
		for (int slot = 1; slot <= 2; slot++) {
			String p = "guardian" + slot + "_";
			put(p + "name", prefixed(slot, "guardian name", "parent name", "guardian", "parent"));
			put(p + "phone", prefixed(slot, "guardian phone", "parent phone", "guardian mobile", "parent mobile",
					"guardian phone number", "parent phone number"));
			put(p + "email", prefixed(slot, "guardian email", "parent email"));
			put(p + "relationship", prefixed(slot, "guardian relationship", "relationship", "relation", "parent relationship"));
		}
		// The first guardian is the one a file means without a number.
		put("guardian1_name", "guardian name", "parent name", "guardian", "parent", "mother", "የወላጅ ስም", "የሞግዚት ስም");
		put("guardian1_phone", "guardian phone", "parent phone", "guardian mobile", "parent mobile", "guardian phone number",
				"parent phone number", "የወላጅ ስልክ", "የሞግዚት ስልክ");
		put("guardian1_email", "guardian email", "parent email", "የወላጅ ኢሜይል");
		put("guardian1_relationship", "guardian relationship", "relationship", "relation", "ግንኙነት", "የወላጅ ግንኙነት");
	}

	private HeaderSynonyms() {
	}

	private static void put(String key, String... synonyms) {
		SYNONYMS.computeIfAbsent(key, (k) -> new java.util.ArrayList<>()).addAll(List.of(synonyms));
	}

	private static String[] prefixed(int slot, String... base) {
		String[] out = new String[base.length * 2];
		int i = 0;
		for (String b : base) {
			out[i++] = b + " " + slot;
			out[i++] = slot + " " + b;
		}
		return out;
	}

	/** Header text reduced to what is compared: letters and digits only, lower case, homophones folded. */
	public static String normalize(String header) {
		if (header == null) {
			return "";
		}
		String folded = PersonNames.foldPart(Normalizer.normalize(header, Normalizer.Form.NFKC));
		StringBuilder out = new StringBuilder(folded.length());
		for (int i = 0; i < folded.length();) {
			int cp = folded.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isLetterOrDigit(cp)) {
				out.appendCodePoint(cp);
			}
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * The suggested mapping of a file: for each field of the type the index of the first still-unused header that
	 * matches the field's key or one of its synonyms. The key itself wins over synonyms (a header that is exactly
	 * {@code class_name} belongs to {@code class_name} even where {@code class} would take it too).
	 */
	public static Map<String, Integer> suggest(ImportType type, List<String> headers) {
		List<String> normalized = headers.stream().map(HeaderSynonyms::normalize).toList();
		Map<String, Integer> mapping = new LinkedHashMap<>();
		Set<Integer> used = new java.util.HashSet<>();
		// Pass 1: exact key. Pass 2: synonyms.
		for (ImportType.Field field : type.fields()) {
			String key = normalize(field.key());
			for (int i = 0; i < normalized.size(); i++) {
				if (!used.contains(i) && normalized.get(i).equals(key)) {
					mapping.put(field.key(), i);
					used.add(i);
					break;
				}
			}
		}
		for (ImportType.Field field : type.fields()) {
			if (mapping.containsKey(field.key())) {
				continue;
			}
			Set<String> synonyms = new java.util.HashSet<>();
			for (String s : SYNONYMS.getOrDefault(field.key(), List.of())) {
				synonyms.add(normalize(s));
			}
			for (int i = 0; i < normalized.size(); i++) {
				if (!used.contains(i) && synonyms.contains(normalized.get(i))) {
					mapping.put(field.key(), i);
					used.add(i);
					break;
				}
			}
		}
		return mapping;
	}

	/** Synonyms of a field for documentation and templates (English and Amharic, as written above). */
	public static List<String> synonymsOf(String key) {
		return List.copyOf(SYNONYMS.getOrDefault(key, List.of()));
	}

}
