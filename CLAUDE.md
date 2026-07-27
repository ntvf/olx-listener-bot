# Guidelines for agents

## Comments

Prefer self-explanatory code over comments. Make the code say what it does — extract a
well-named method, name a variable for the intermediate value, split a condition into a
named boolean — instead of narrating it in a comment.

Only write a comment when the intent genuinely cannot be expressed in code and the reader
would otherwise be misled or surprised: a non-obvious *why* (a workaround, an external
constraint, a business rule), or a warning about a subtle invariant. Keep those short and
high-level. Do not add comments that restate the code, mark sections, or explain what a
good method name already tells you.
