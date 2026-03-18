declare module "yt-search" {
  interface VideoResult {
    title: string;
    url: string;
    videoId: string;
    duration: { seconds: number; timestamp: string };
    ago: string;
    views: number;
    author: { name: string };
  }
  interface SearchResult {
    videos: VideoResult[];
  }
  function yts(query: string): Promise<SearchResult>;
  export = yts;
}
