# frozen_string_literal: true

require "uri"
require "ipaddr"

origin = ARGV.fetch(0, "").strip
valid = begin
  uri = URI.parse(origin)
  host = uri.host.to_s.downcase
  labels = host.split(".")
  address = IPAddr.new(host) rescue nil
  public_ipv4 = address&.ipv4? && ![
    IPAddr.new("0.0.0.0/8"), IPAddr.new("10.0.0.0/8"), IPAddr.new("100.64.0.0/10"),
    IPAddr.new("127.0.0.0/8"), IPAddr.new("169.254.0.0/16"), IPAddr.new("172.16.0.0/12"),
    IPAddr.new("192.168.0.0/16"), IPAddr.new("224.0.0.0/4")
  ].any? { |range| range.include?(address) }
  public_domain = labels.length >= 2 && labels.last.match?(/\A[a-z]{2,63}\z/) &&
    labels.all? { |label| label.match?(/\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\z/i) }
  uri.scheme == "https" && uri.userinfo.nil? && uri.path.to_s.empty? &&
    uri.query.nil? && uri.fragment.nil? && (uri.port == 443 || (1..65_535).cover?(uri.port)) &&
    host.length.between?(4, 253) && (public_ipv4 || public_domain) &&
    host != "localhost" && host != "example.com" &&
    %w[.example.com .example .invalid .test .local].none? { |suffix| host.end_with?(suffix) }
rescue URI::InvalidURIError
  false
end

abort("error: Release requires CATCHZOON_API_BASE_URL=https://<public-domain-or-fixed-public-ip> with no path, credentials, or placeholder host.") unless valid
