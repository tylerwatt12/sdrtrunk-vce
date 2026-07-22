#!/usr/bin/env bash

set -u

root="$(git rev-parse --show-toplevel)"
cd "$root"

# Keep policy tokens out of the repository while still matching them case-insensitively.
term_one="$(printf '\144\145\143\162\171\160\164')"
term_two="$(printf '\145\156\143\162\171\160\164')"
pattern="${term_one}|${term_two}"

is_publication_path()
{
    case "$1" in
        README|README.*|CHANGELOG|CHANGELOG.*|RELEASING|RELEASING.*|NOTICE|NOTICE.*|\
        *.md|*.markdown|*.rst|*.adoc|*.html|*.htm|*.txt|*.csv|*.tsv|*.tar.gz|*.tgz|\
        .github/*.json|.github/*.yml|.github/*.yaml|\
        .github/**/*.json|.github/**/*.yml|.github/**/*.yaml)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

is_publication_exception()
{
    case "$1" in
        # This format specification must name the exact serialized metadata fields. Keep this exception path-specific.
        docs/mbe-call-sequence-recording-format.md)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

scan_stream()
{
    local label="$1"
    local lines

    lines="$(LC_ALL=C grep -aEin "$pattern" | cut -d: -f1 | paste -sd, - || true)"
    if [[ -n "$lines" ]]; then
        printf 'Publication policy violation in %s (line(s) %s).\n' "$label" "$lines" >&2
        return 1
    fi

    return 0
}

scan_value()
{
    local label="$1"
    local value="$2"
    printf '%s' "$value" | scan_stream "$label"
}

scan_blob()
{
    local object="$1"
    local label="$2"
    local path="$3"
    local failed=0

    case "$path" in
        *.tar.gz|*.tgz)
            if ! git cat-file blob "$object" | tar -tzf - | scan_stream "$label entries"; then
                failed=1
            fi
            if ! git cat-file blob "$object" | tar -xOzf - | scan_stream "$label contents"; then
                failed=1
            fi
            ;;
        *)
            if ! git cat-file blob "$object" | scan_stream "$label"; then
                failed=1
            fi
            ;;
    esac

    return "$failed"
}

scan_tree()
{
    local ref="$1"
    local path
    local failed=0

    while IFS= read -r -d '' path; do
        if is_publication_path "$path" && ! is_publication_exception "$path"; then
            if ! scan_value "$ref path" "$path"; then
                failed=1
            fi
            if ! scan_blob "$ref:$path" "$path" "$path"; then
                failed=1
            fi
        fi
    done < <(git ls-tree -r -z --name-only "$ref")

    return "$failed"
}

scan_staged()
{
    local path
    local failed=0

    while IFS= read -r -d '' path; do
        if is_publication_path "$path" && ! is_publication_exception "$path"; then
            if ! scan_value "staged path" "$path"; then
                failed=1
            fi
            if ! scan_blob ":$path" "$path" "$path"; then
                failed=1
            fi
        fi
    done < <(git diff --cached --name-only --diff-filter=ACMR -z)

    return "$failed"
}

scan_commit()
{
    local commit="$1"
    local path
    local failed=0
    local message

    message="$(git show -s --format=%B "$commit")"
    if ! scan_value "commit $commit message" "$message"; then
        failed=1
    fi

    while IFS= read -r -d '' path; do
        if is_publication_path "$path" && ! is_publication_exception "$path" &&
            git cat-file -e "$commit:$path" 2>/dev/null; then
            if ! scan_value "commit $commit path" "$path"; then
                failed=1
            fi
            if ! scan_blob "$commit:$path" "$commit:$path" "$path"; then
                failed=1
            fi
        fi
    done < <(git diff-tree --root --no-commit-id --name-only --diff-filter=ACMR -r -z "$commit")

    return "$failed"
}

scan_commits()
{
    local commit
    local failed=0

    while IFS= read -r commit; do
        if [[ -n "$commit" ]] && ! scan_commit "$commit"; then
            failed=1
        fi
    done

    return "$failed"
}

usage()
{
    printf 'Usage: %s --staged | --message-file FILE | --tree REF | --range RANGE | --commits | --stdin LABEL\n' "$0" >&2
    exit 2
}

[[ $# -ge 1 ]] || usage

case "$1" in
    --staged)
        [[ $# -eq 1 ]] || usage
        scan_staged
        ;;
    --message-file)
        [[ $# -eq 2 ]] || usage
        scan_stream "commit message" < "$2"
        ;;
    --tree)
        [[ $# -eq 2 ]] || usage
        scan_tree "$2"
        ;;
    --range)
        [[ $# -eq 2 ]] || usage
        git rev-list --reverse "$2" | scan_commits
        ;;
    --commits)
        [[ $# -eq 1 ]] || usage
        scan_commits
        ;;
    --stdin)
        [[ $# -eq 2 ]] || usage
        scan_stream "$2"
        ;;
    *)
        usage
        ;;
esac
