library(ggplot2)
df <- read.csv("latencies8.csv", header=T)

plot_labels <- labs(title="Mean latency (ms) vs distance between two devices (m)", subtitle="8 byte message", xlab="Distance between devices (m)", ylab="Mean latency (ms)")
p <- ggplot(df, aes(x=distance, y=mean_lat)) + geom_smooth(span=.2) + plot_labels
p
p + geom_errorbar(aes(ymin=mean_lat - std, ymax=mean_lat + std))

