# Use phusion/baseimage as base image. To make your builds reproducible, make
# sure you lock down to a specific version, not to `latest`!
# See https://github.com/phusion/baseimage-docker/blob/master/Changelog.md for
# a list of version numbers.
FROM phusion/baseimage:0.9.11

# Set correct environment variables.
ENV HOME /root

# Disable SSH
RUN rm -rf /etc/service/sshd /etc/my_init.d/00_regen_ssh_host_keys.sh
# Disable cron
RUN rm -rf /etc/service/cron

# Use baseimage-docker's init system.
CMD ["/sbin/my_init"]

RUN apt-get update

# Install java
RUN apt-get -y install openjdk-7-jre-headless ant openjdk-7-jdk

# Install mongo
RUN apt-get -y install mongodb

ADD . /opt/app
RUN rm -rf /opt/app/.git

RUN cd /opt/app && ant dist-server

EXPOSE 9898

RUN apt-get install -y wget

# Clean up APT when done.
RUN apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

RUN rm -rf /usr/share/vim /usr/share/doc /usr/share/man /var/lib/dpkg /var/lib/belocs /var/lib/ucf /var/cache/debconf /var/log/*.log