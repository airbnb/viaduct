import argparse
import re
import subprocess

# Generate changelog between two git refs
# Usage: python generate_changelog.py <commit1> <commit2>
# Example: python generate_changelog.py v1.0.0 v1.1.0
# This will output the changelog entries between the two commits, including co-authors formatted as GitHub usernames.
def main():
  parser = argparse.ArgumentParser(description='Generate changelog between two git refs.')
  parser.add_argument('commit1', help='First git ref')
  parser.add_argument('commit2', help='Second git ref')
  args = parser.parse_args()

  git_cmd = [
    'git', 'log',
    f'{args.commit1}..{args.commit2}',
    '--format=format:%h %s by AUTHORS_START%(trailers:key=Co-authored-by,valueonly,separator=%x7C)AUTHORS_END %n'
  ]

  result = subprocess.run(git_cmd, capture_output=True, text=True)

  entries = result.stdout.splitlines()

  formatted_entries = [format_entry(entry) for entry in entries]

  changelog = "\n".join(formatted_entries)

  print(changelog)

def format_entry(entry):
  author_start_idx = entry.find('AUTHORS_START') + 'AUTHORS_START'.__len__()
  author_end_idx = entry.find('AUTHORS_END')

  authors_segment = entry[author_start_idx:author_end_idx]
  authors = authors_segment.split('|')
  usernames = [extract_username(author_str) for author_str in authors]

  commit_info = entry[:author_start_idx - 'AUTHORS_START'.__len__()].strip()

  return commit_info + ' ' + ', '.join(usernames)

def extract_username(author_line: str) -> str:
  match = re.search(r'<([^@]+)@', author_line)
  if match:
    return "@" + match.group(1)
  return ""

if __name__ == '__main__':
  main()
