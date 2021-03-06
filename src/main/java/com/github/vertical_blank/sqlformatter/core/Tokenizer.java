package com.github.vertical_blank.sqlformatter.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.vertical_blank.sqlformatter.core.util.JSLikeList;
import com.github.vertical_blank.sqlformatter.core.util.Util;


public class Tokenizer {
	private final Pattern WHITESPACE_PATTERN;
	private final Pattern NUMBER_PATTERN;
	private final Pattern OPERATOR_PATTERN;

	private final Pattern BLOCK_COMMENT_PATTERN;
	private final Pattern LINE_COMMENT_PATTERN;

	private final Pattern RESERVED_TOPLEVEL_PATTERN;
	private final Pattern RESERVED_NEWLINE_PATTERN;
	private final Pattern RESERVED_PLAIN_PATTERN;

	private final Pattern WORD_PATTERN;
	private final Pattern STRING_PATTERN;

	private final Pattern OPEN_PAREN_PATTERN;
	private final Pattern CLOSE_PAREN_PATTERN;

	private final Pattern INDEXED_PLACEHOLDER_PATTERN;
	private final Pattern IDENT_NAMED_PLACEHOLDER_PATTERN;
	private final Pattern STRING_NAMED_PLACEHOLDER_PATTERN;


	/**
	 * @param cfg {String[]} cfg.reservedWords Reserved words in SQL
	 *            {String[]} cfg.reservedToplevelWords Words that are set to new line separately
	 *            {String[]} cfg.reservedNewlineWords Words that are set to newline
	 *            {String[]} cfg.stringTypes String types to enable: "", '', ``, [], N''
	 *            {String[]} cfg.openParens Opening parentheses to enable, like (, [
	 *            {String[]} cfg.closeParens Closing parentheses to enable, like ), ]
	 *            {String[]} cfg.indexedPlaceholderTypes Prefixes for indexed placeholders, like ?
	 *            {String[]} cfg.namedPlaceholderTypes Prefixes for named placeholders, like @ and :
	 *            {String[]} cfg.lineCommentTypes Line comments to enable, like # and --
	 *            {String[]} cfg.specialWordChars Special chars that can be found inside of words, like @ and #
	 */
	public Tokenizer(DialectConfig cfg) {
		this.WHITESPACE_PATTERN = Pattern.compile("^(\\s+)");
		this.NUMBER_PATTERN = Pattern.compile("^((-\\s*)?[0-9]+(\\.[0-9]+)?|0x[0-9a-fA-F]+|0b[01]+)\\b");
		this.OPERATOR_PATTERN = Pattern.compile("^(!=|<>|==|<=|>=|!<|!>|\\|\\||::|->>|=>|->|~~\\*|~~|!~~\\*|!~~|~\\*|!~\\*|!~|.)");

//        this.BLOCK_COMMENT_REGEX = /^(\/\*[^]*?(?:\*\/|$))/;
		this.BLOCK_COMMENT_PATTERN = Pattern.compile("^(/\\*(?s).*?(?:\\*/|$))");
		this.LINE_COMMENT_PATTERN = Pattern.compile(this.createLineCommentRegex(new JSLikeList<>(cfg.lineCommentTypes)));

		this.RESERVED_TOPLEVEL_PATTERN = Pattern.compile(this.createReservedWordRegex(new JSLikeList<>(cfg.reservedToplevelWords)));
		this.RESERVED_NEWLINE_PATTERN = Pattern.compile(this.createReservedWordRegex(new JSLikeList<>(cfg.reservedNewlineWords)));
		this.RESERVED_PLAIN_PATTERN = Pattern.compile(this.createReservedWordRegex(new JSLikeList<>(cfg.reservedWords)));

		this.WORD_PATTERN = Pattern.compile(this.createWordRegex(new JSLikeList<>(cfg.specialWordChars)));
		this.STRING_PATTERN = Pattern.compile(this.createStringRegex(new JSLikeList<>(cfg.stringTypes)));

		this.OPEN_PAREN_PATTERN = Pattern.compile(this.createParenRegex(new JSLikeList<>(cfg.openParens)));
		this.CLOSE_PAREN_PATTERN = Pattern.compile(this.createParenRegex(new JSLikeList<>(cfg.closeParens)));


		this.INDEXED_PLACEHOLDER_PATTERN = createPlaceholderRegexPattern(new JSLikeList<>(cfg.indexedPlaceholderTypes), "[0-9]*");
		this.IDENT_NAMED_PLACEHOLDER_PATTERN = createPlaceholderRegexPattern(new JSLikeList<>(cfg.namedPlaceholderTypes), "[a-zA-Z0-9._$]+");
		this.STRING_NAMED_PLACEHOLDER_PATTERN = createPlaceholderRegexPattern(
						new JSLikeList<>(cfg.namedPlaceholderTypes),
						this.createStringPattern(new JSLikeList<>(cfg.stringTypes))
		);
	}

	private String createLineCommentRegex(JSLikeList<String> lineCommentTypes) {
		return String.format(
						"^((?:%s).*?(?:\n|$))",
						lineCommentTypes.map(Util::escapeRegExp).join("|")
		);
	}

	private String createReservedWordRegex(JSLikeList<String> reservedWords) {
		String reservedWordsPattern = reservedWords.join("|").replaceAll(" ", "\\\\s+");
		return "(?i)" + "^(" + reservedWordsPattern + ")\\b";
	}

	private String createWordRegex(JSLikeList<String> specialChars) {
		return "^([\\w" + specialChars.join("") + "]+)";
	}

	private String createStringRegex(JSLikeList<String> stringTypes) {
		return "^(" + this.createStringPattern(stringTypes) + ")";
	}

	// This enables the following string patterns:
	// 1. backtick quoted string using `` to escape
	// 2. square bracket quoted string (SQL Server) using ]] to escape
	// 3. double quoted string using "" or \" to escape
	// 4. single quoted string using '' or \' to escape
	// 5. national character quoted string using N'' or N\' to escape
	private String createStringPattern(JSLikeList<String> stringTypes) {
		Map<String, String> patterns = new HashMap<>();
		patterns.put("``", "((`[^`]*($|`))+)");
		patterns.put("[]", "((\\[[^\\]]*($|\\]))(\\][^\\]]*($|\\]))*)");
		patterns.put("\"\"", "((\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*(\"|$))+)");
		patterns.put("''", "(('[^'\\\\]*(?:\\\\.[^'\\\\]*)*('|$))+)");
		patterns.put("N''", "((N'[^N'\\\\]*(?:\\\\.[^N'\\\\]*)*('|$))+)");

		return stringTypes.map(patterns::get).join("|");
	}

	private String createParenRegex(JSLikeList<String> parens) {
		return "(?i)" + "^(" + parens.map(Tokenizer::escapeParen).join("|") + ")";
	}

	private static String escapeParen(String paren) {
		if (paren.length() == 1) {
			// A single punctuation character
			return Util.escapeRegExp(paren);
		} else {
			// longer word
			return "\\b" + paren + "\\b";
		}
	}

	private static Pattern createPlaceholderRegexPattern(JSLikeList<String> types, String pattern) {
		if (types.isEmpty()) {
			return null;
		}
		String typesRegex = types.map(Util::escapeRegExp).join("|");

		return Pattern.compile(String.format("^((?:%s)(?:%s))", typesRegex, pattern));
	}

	/**
	 * Takes a SQL string and breaks it into tokens.
	 * Each token is an object with type and value.
	 *
	 * @param input input The SQL string
	 * @return {Object[]} tokens An array of tokens.
	 */
	List<Token> tokenize(String input) {
		List<Token> tokens = new ArrayList<>();
		Token token = null;

		// Keep processing the string until it is empty
		while (input.length() != 0) {
			// Get the next token and the token type
			token = this.getNextToken(input, token);
			// Advance the string
			input = input.substring(token.value.length());

			tokens.add(token);
		}
		return tokens;
	}

	private Token getNextToken(String input, Token previousToken) {
		return Util.firstNotnull(
						() -> this.getWhitespaceToken(input),
						() -> this.getCommentToken(input),
						() -> this.getStringToken(input),
						() -> this.getOpenParenToken(input),
						() -> this.getCloseParenToken(input),
						() -> this.getPlaceholderToken(input),
						() -> this.getNumberToken(input),
						() -> this.getReservedWordToken(input, previousToken),
						() -> this.getWordToken(input),
						() -> this.getOperatorToken(input));
	}

	private Token getWhitespaceToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.WHITESPACE,
						this.WHITESPACE_PATTERN
		);
	}

	private Token getCommentToken(String input) {
		return Util.firstNotnull(
						() -> this.getLineCommentToken(input),
						() -> this.getBlockCommentToken(input));
	}

	private Token getLineCommentToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.LINE_COMMENT,
						this.LINE_COMMENT_PATTERN
		);
	}

	private Token getBlockCommentToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.BLOCK_COMMENT,
						this.BLOCK_COMMENT_PATTERN
		);
	}

	private Token getStringToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.STRING,
						this.STRING_PATTERN
		);
	}

	private Token getOpenParenToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.OPEN_PAREN,
						this.OPEN_PAREN_PATTERN
		);
	}

	private Token getCloseParenToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.CLOSE_PAREN,
						this.CLOSE_PAREN_PATTERN
		);
	}

	private Token getPlaceholderToken(String input) {
		return Util.firstNotnull(
						() -> this.getIdentNamedPlaceholderToken(input),
						() -> this.getStringNamedPlaceholderToken(input),
						() -> this.getIndexedPlaceholderToken(input));
	}

	private Token getIdentNamedPlaceholderToken(String input) {
		return this.getPlaceholderTokenWithKey(
						input,
						this.IDENT_NAMED_PLACEHOLDER_PATTERN,
						v -> v.substring(1)
		);
	}

	private Token getStringNamedPlaceholderToken(String input) {
		return this.getPlaceholderTokenWithKey(
						input,
						this.STRING_NAMED_PLACEHOLDER_PATTERN,
						v -> this.getEscapedPlaceholderKey(v.substring(2, v.length() - 1), v.substring(v.length() - 1))
		);
	}

	private Token getIndexedPlaceholderToken(String input) {
		return this.getPlaceholderTokenWithKey(
						input,
						this.INDEXED_PLACEHOLDER_PATTERN,
						v -> v.substring(1)
		);
	}

	private Token getPlaceholderTokenWithKey(String input, Pattern regex, java.util.function.Function<String, String> parseKey) {
		Token token = this.getTokenOnFirstMatch(input, TokenTypes.PLACEHOLDER, regex);
		if (token != null) {
			token.key = parseKey.apply(token.value);
		}
		return token;
	}

	private String getEscapedPlaceholderKey(String key, String quoteChar) {
		return key.replaceAll(Util.escapeRegExp("\\") + quoteChar, quoteChar);
	}

	// Decimal, binary, or hex numbers
	private Token getNumberToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.NUMBER,
						this.NUMBER_PATTERN
		);
	}

	// Punctuation and symbols
	private Token getOperatorToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.OPERATOR,
						this.OPERATOR_PATTERN
		);
	}

	private Token getReservedWordToken(String input, Token previousToken) {
		// A reserved word cannot be preceded by a "."
		// this makes it so in "mytable.from", "from" is not considered a reserved word
		if (previousToken != null && previousToken.value != null && previousToken.value.equals(".")) {
			return null;
		}
		return Util.firstNotnull(
						() -> this.getToplevelReservedToken(input),
						() -> this.getNewlineReservedToken(input),
						() -> this.getPlainReservedToken(input));
	}

	private Token getToplevelReservedToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.RESERVED_TOPLEVEL,
						this.RESERVED_TOPLEVEL_PATTERN
		);
	}

	private Token getNewlineReservedToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.RESERVED_NEWLINE,
						this.RESERVED_NEWLINE_PATTERN
		);
	}

	private Token getPlainReservedToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.RESERVED,
						this.RESERVED_PLAIN_PATTERN
		);
	}

	private Token getWordToken(String input) {
		return this.getTokenOnFirstMatch(
						input,
						TokenTypes.WORD,
						this.WORD_PATTERN
		);
	}

	private String getFirstMatch(String input, Pattern regex) {
		if (regex == null) {
			return null;
		}

		Matcher matcher = regex.matcher(input);
		if (matcher.find()) {
			return matcher.group();
		} else {
			return null;
		}
	}

	private Token getTokenOnFirstMatch(String input, TokenTypes type, Pattern regex) {
		String matches = getFirstMatch(input, regex);

		if (matches != null) {
			return new Token(type, matches);
		} else {
			return null;
		}
	}

}
