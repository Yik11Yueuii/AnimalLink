ALTER TABLE timeline_entry
  DROP INDEX uk_timeline_source,
  ADD UNIQUE KEY uk_timeline_source_entry (source_type, source_id, entry_type);
