from typedllm.core.validation import validate_output


SCHEMA = {
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "age": {"type": "integer"},
    },
    "required": ["name", "age"],
    "additionalProperties": False,
}


def test_valid_output_has_no_issues():
    data = {
        "name": "Alice",
        "age": 21,
    }

    issues = validate_output(data, SCHEMA)

    assert issues == ()


def test_wrong_type_is_reported():
    data = {
        "name": "Alice",
        "age": "21",
    }

    issues = validate_output(data, SCHEMA)

    assert len(issues) == 1
    assert issues[0].path == "$.age"
    assert issues[0].rule == "type"
    assert "integer" in issues[0].message


def test_missing_required_field_is_reported():
    data = {
        "name": "Alice",
    }

    issues = validate_output(data, SCHEMA)

    assert len(issues) == 1
    assert issues[0].path == "$"
    assert issues[0].rule == "required"
    assert "age" in issues[0].message


def test_additional_property_is_rejected():
    data = {
        "name": "Alice",
        "age": 21,
        "extra": "not allowed",
    }

    issues = validate_output(data, SCHEMA)

    assert len(issues) == 1
    assert issues[0].rule == "additionalProperties"
    assert "extra" in issues[0].message
